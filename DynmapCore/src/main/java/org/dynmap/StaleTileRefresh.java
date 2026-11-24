package org.dynmap;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.dynmap.hdmap.HDMap;
import org.dynmap.hdmap.HDMapTile;
import org.dynmap.storage.MapStorageTile;

/** Low-priority refresh of the oldest existing base tiles, including unoccupied areas. */
final class StaleTileRefresh implements AutoCloseable {
    record Candidate(MapStorageTile stored, HDMapTile render, long timestamp) {}
    static final class Selection {
        private final int limit;
        private final PriorityQueue<Candidate> oldest = new PriorityQueue<>(Comparator.comparingLong(Candidate::timestamp).reversed());
        private final Map<HDMapTile, Candidate> selected = new HashMap<>();
        Selection(int limit) { this.limit = limit; }
        void offer(Candidate candidate) {
            Candidate previous = selected.get(candidate.render);
            if (previous != null) {
                if (previous.timestamp <= candidate.timestamp) return;
                oldest.remove(previous); selected.remove(previous.render);
            }
            if (oldest.size() >= limit) {
                if (oldest.peek().timestamp <= candidate.timestamp) return;
                selected.remove(oldest.remove().render);
            }
            oldest.add(candidate); selected.put(candidate.render, candidate);
        }
        int size() { return oldest.size(); }
        List<Candidate> results() {
            var result = new ArrayList<>(oldest); result.sort(Comparator.comparingLong(Candidate::timestamp)); return result;
        }
    }
    private record Scope(DynmapWorld world,HDMap map) {}
    private record ScanResult(int scope,org.dynmap.storage.filetree.TileScanCursor cursor,List<Candidate> candidates,boolean complete) {}
    private final MapManager manager;
    private final TileRefreshHistory history;
    private final long maxAgeMillis,scanIntervalNanos,startupDeadline,checkpointInterval;
    private final int maxCandidates,backlogLimit;
    private final double speedPercent;
    private final ScheduledExecutorService worker;
    private final ExecutorService scanner;
    private final ArrayDeque<Candidate> pending=new ArrayDeque<>();
    private Future<ScanResult> scan;
    private List<Scope> scopes=List.of();
    private int scopeIndex;
    private org.dynmap.storage.filetree.TileScanCursor cursor=new org.dynmap.storage.filetree.TileScanCursor();
    private boolean scanCycle,restored;
    private final java.util.concurrent.atomic.AtomicLong submitted=new java.util.concurrent.atomic.AtomicLong(),completed=new java.util.concurrent.atomic.AtomicLong(),expired=new java.util.concurrent.atomic.AtomicLong(),failed=new java.util.concurrent.atomic.AtomicLong();
    private static final class Attempt {
        final Candidate candidate;volatile boolean started;
        final java.util.concurrent.atomic.AtomicBoolean successful=new java.util.concurrent.atomic.AtomicBoolean();
        Attempt(Candidate candidate){this.candidate=candidate;}
    }
    private final ConcurrentHashMap<HDMapTile,Attempt> awaiting=new ConcurrentHashMap<>();
    private volatile int pendingCount;
    private volatile long lastScanStarted,lastScanFinished,lastCompleted,nextRender;
    private volatile boolean scanningNow,closed;
    private volatile String errorState;
    private long nextScan,nextMaintenance,nextCheckpoint;
    private ProgressCheckpoint checkpoint;

    StaleTileRefresh(DynmapCore core,MapManager manager)throws IOException {
        this.manager=manager;var config=core.configuration;
        int days=bounded(config.getInteger("stale-tile-refresh/max-age-days",60),1,3650);
        speedPercent=StaleTileRefreshSettings.speedPercent(config.getDouble("stale-tile-refresh/render-speed-percent",10));
        maxCandidates=bounded(config.getInteger("stale-tile-refresh/max-tiles-per-scan",4096),1,65536);
        backlogLimit=bounded(config.getInteger("stale-tile-refresh/backlog-limit",20),1,10000);
        maxAgeMillis=TimeUnit.DAYS.toMillis(days);
        scanIntervalNanos=TimeUnit.HOURS.toNanos(bounded(config.getInteger("stale-tile-refresh/scan-interval-hours",24),1,720));
        startupDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(bounded(config.getInteger("stale-tile-refresh/startup-delay-seconds",300),0,86400));
        nextScan=startupDeadline;checkpointInterval=ProgressCheckpoint.intervalMillis(config);
        history=new TileRefreshHistory(core.getDataFolder().toPath().resolve("tile-refresh-history"),core.getFile(core.getWebPath()).toPath());
        scopes=currentScopes();
        if(ProgressCheckpoint.enabled(config))try{checkpoint=new ProgressCheckpoint(core,"stale-tile-scan");}
        catch(IOException|RuntimeException invalid){Log.warning("Stale refresh checkpoint unavailable or invalid; restarting the scan safely");}
        worker=Executors.newSingleThreadScheduledExecutor(r->thread(r,"MCCMap stale-tile worker"));
        scanner=Executors.newSingleThreadExecutor(r->thread(r,"MCCMap stale-tile scan"));
        worker.scheduleWithFixedDelay(this::tick,100,100,TimeUnit.MILLISECONDS);
        Log.info("Stale tile refresh enabled: "+days+" days, target "+speedPercent+"% of full-render speed, one background job at a time");
    }
    private static Thread thread(Runnable job,String name){Thread t=new Thread(job,name);t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;}
    private static int bounded(int value,int min,int max){return Math.max(min,Math.min(max,value));}
    static boolean stale(long timestamp,long now,long maxAgeMillis){return timestamp>0&&timestamp<=now&&now-timestamp>=maxAgeMillis;}
    static long lastRenderTime(long storage,long history,long now){return Math.max(storage>0&&storage<=now?storage:0,history>0&&history<=now?history:0);}
    void recordSuccessful(MapStorageTile tile,long timestamp){if(!closed&&tile.zoom==0)history.record(TileRefreshHistory.namespace(tile),tile.x,tile.y,timestamp);}
    void recordStarted(HDMapTile tile){var attempt=awaiting.get(tile);if(attempt!=null)attempt.started=true;}
    void recordCompleted(HDMapTile tile){var a=awaiting.get(tile);if(!closed&&a!=null&&a.successful.compareAndSet(false,true)){completed.incrementAndGet();lastCompleted=System.currentTimeMillis();if(!a.started)awaiting.remove(tile,a);}}
    void recordFinished(HDMapTile tile,long elapsed,long slice,int parallel){var a=awaiting.get(tile);if(a!=null){nextRender=System.nanoTime()+StaleTileRefreshSettings.cooldownNanos(elapsed,slice,parallel,speedPercent);if(awaiting.remove(tile,a)&&!a.successful.get())failed.incrementAndGet();}}
    boolean owns(MapTile tile){return awaiting.containsKey(tile);}
    Set<MapTile> ownedTiles(){return new HashSet<>(awaiting.keySet());}
    List<String> describe(){return List.of("Stale tile refresh:","  State: "+(errorState!=null?errorState:closed?"stopped":scanningNow?"scanning":manager.stalePauseReason(backlogLimit)!=null?"paused: "+manager.stalePauseReason(backlogLimit):pendingCount>0?"dispatching":"waiting"),
        "  Pending: "+pendingCount+"; awaiting render: "+awaiting.size(),"  Since startup: submitted="+submitted+", completed="+completed+", expired="+expired+", failed="+failed,
        "  Scan progress: map "+Math.min(scopeIndex+1,scopes.size())+" of "+scopes.size()+"; restart checkpoint "+(checkpoint==null?"unavailable":"enabled"),
        "  Eligibility: "+TimeUnit.MILLISECONDS.toDays(maxAgeMillis)+" days; target: "+speedPercent+"% of full-render speed; one background job at a time");}
    private List<Scope> currentScopes(){return manager.getWorlds().stream().sorted(Comparator.comparing(DynmapWorld::getName)).flatMap(w->w.maps.stream().filter(HDMap.class::isInstance).map(HDMap.class::cast).sorted(Comparator.comparing(HDMap::getName)).map(m->new Scope(w,m))).toList();}
    private String layout(){return scopes.stream().map(s->s.world.getName()+":"+s.map.getName()+":"+s.map.getPrefix()).toList().toString();}
    private ConfigurationNode encode(Candidate c){var n=new ConfigurationNode();n.put("world",c.stored.world.getName());n.put("map",c.stored.map.getName());n.put("layout",c.stored.map.getPrefix());n.put("variant",c.stored.var.variantSuffix);n.put("x",c.stored.x);n.put("y",c.stored.y);n.put("timestamp",c.timestamp);return n;}
    private Candidate decode(ConfigurationNode n){
        var world=manager.getWorld(n.getString("world",""));if(world==null)return null;
        for(var map:world.maps)if(map instanceof HDMap hd&&map.getName().equals(n.getString("map",""))&&map.getPrefix().equals(n.getString("layout","")))
            for(var variant:map.getVariants())if(variant.variantSuffix.equals(n.getString("variant",""))){var tile=world.getMapStorage().getTile(world,map,n.getInteger("x",0),n.getInteger("y",0),0,variant);tile.lastModified=tile.getLastModified();return new Candidate(tile,new HDMapTile(world,hd.getPerspective(),tile.x,tile.y,hd.getBoostZoom(),hd.getTileScale()),(long)n.getLong("timestamp",0));}
        return null;
    }
    private void restore()throws IOException {
        var saved=checkpoint.read();if(saved==null)return;
        if(!layout().equals(saved.getString("layout","")))return;
        scopeIndex=Math.max(0,Math.min(scopes.size(),saved.getInteger("scope",0)));scanCycle=saved.getBoolean("active",false);
        var state=saved.getNode("cursor");if(state!=null)cursor=new org.dynmap.storage.filetree.TileScanCursor(state);
        var jobs=saved.getNodes("pending");if(jobs!=null){if(jobs.size()>200000)throw new IOException("Stale checkpoint queue limit");for(var n:jobs){var c=decode(n);if(c!=null)pending.add(c);}}
        long remaining=Math.max(0,Math.min(TimeUnit.DAYS.toMillis(7),(long)saved.getLong("next-render",0)-System.currentTimeMillis()));nextRender=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(remaining);
        if(!scanCycle)nextScan=Math.max(startupDeadline,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.max(0,Math.min(TimeUnit.DAYS.toMillis(30),(long)saved.getLong("next-scan",0)-System.currentTimeMillis()))));
        pendingCount=pending.size();Log.info("Restored stale scan progress and "+pending.size()+" paced refresh jobs");
    }
    private void saveCheckpoint(){if(checkpoint==null||!restored)return;try{
        history.flush();var n=new ConfigurationNode();n.put("layout",layout());n.put("scope",scopeIndex);n.put("cursor",cursor.save());n.put("active",scanCycle);
        var jobs=new LinkedHashMap<HDMapTile,Candidate>();pending.forEach(c->jobs.put(c.render,c));awaiting.values().forEach(a->jobs.put(a.candidate.render,a.candidate));n.put("pending",jobs.values().stream().map(this::encode).toList());
        n.put("next-render",System.currentTimeMillis()+TimeUnit.NANOSECONDS.toMillis(Math.max(0,nextRender-System.nanoTime())));n.put("next-scan",System.currentTimeMillis()+TimeUnit.NANOSECONDS.toMillis(Math.max(0,nextScan-System.nanoTime())));checkpoint.write(n);
    }catch(IOException failure){Log.warning("Could not save stale scan progress; the previous checkpoint is retained");}}
    private ScanResult scanSlice(int index,org.dynmap.storage.filetree.TileScanCursor position)throws IOException {
        if(index>=scopes.size())return new ScanResult(index,position,List.of(),true);
        var scope=scopes.get(index);if(!scope.world.isLoaded())return new ScanResult(index+1,new org.dynmap.storage.filetree.TileScanCursor(),List.of(),index+1>=scopes.size());
        var selected=new Selection(maxCandidates);long now=System.currentTimeMillis();
        boolean done=((org.dynmap.storage.filetree.FileTreeMapStorage)scope.world.getMapStorage()).scanTiles(scope.world,scope.map,position,Math.min(4096,maxCandidates),stored->{
            if(stored.zoom!=0)return;
            try{long timestamp=lastRenderTime(stored.lastModified,history.lastSuccessful(TileRefreshHistory.namespace(stored),stored.x,stored.y),now);if(stale(timestamp,now,maxAgeMillis))selected.offer(new Candidate(stored,new HDMapTile(scope.world,scope.map.getPerspective(),stored.x,stored.y,scope.map.getBoostZoom(),scope.map.getTileScale()),timestamp));}
            catch(IOException failure){throw new CompletionException(failure);}
        });
        return new ScanResult(done?index+1:index,done?new org.dynmap.storage.filetree.TileScanCursor():position,selected.results(),done&&index+1>=scopes.size());
    }
    private void tick(){if(closed)return;try{
        long now=System.nanoTime();
        if(!restored){if(now<startupDeadline)return;scopes=currentScopes();if(scopes.isEmpty())return;
            try{if(checkpoint!=null)restore();}catch(IOException|RuntimeException invalid){scopeIndex=0;cursor=new org.dynmap.storage.filetree.TileScanCursor();pending.clear();scanCycle=false;nextScan=startupDeadline;Log.warning("Stale scan checkpoint invalid; restarting this scan safely");}
            restored=true;
        }
        if(now>=nextMaintenance){history.flush();nextMaintenance=now+TimeUnit.SECONDS.toNanos(1);}
        if(scan!=null&&scan.isDone()){var result=scan.get();scan=null;scopeIndex=result.scope;cursor=result.cursor;pending.addAll(result.candidates);if(!result.candidates.isEmpty())Log.verboseinfo("Stale tile scan selected "+result.candidates.size()+" refresh jobs");if(result.complete){scanCycle=false;scanningNow=false;lastScanFinished=System.currentTimeMillis();nextScan=now+scanIntervalNanos;}if(result.complete)nextCheckpoint=0;}
        if(System.currentTimeMillis()>=nextCheckpoint){saveCheckpoint();nextCheckpoint=System.currentTimeMillis()+checkpointInterval;}
        if(now<startupDeadline)return;
        if(!scanCycle&&pending.isEmpty()&&awaiting.isEmpty()&&now>=nextScan){scopes=currentScopes();scopeIndex=0;cursor=new org.dynmap.storage.filetree.TileScanCursor();scanCycle=true;scanningNow=true;lastScanStarted=System.currentTimeMillis();}
        if(scanCycle&&scan==null&&pending.isEmpty()){scanningNow=true;int index=scopeIndex;var copy=new org.dynmap.storage.filetree.TileScanCursor(cursor.save());scan=scanner.submit(()->scanSlice(index,copy));}
        if(!awaiting.isEmpty()||now<nextRender||!manager.canRefreshStaleTiles(backlogLimit))return;
        for(int checks=0;checks<32&&!pending.isEmpty();checks++){
            var candidate=pending.removeFirst();var stored=candidate.stored;
            if(manager.getWorld(stored.world.getName())!=stored.world||!stored.world.isLoaded()||!stored.world.maps.contains(stored.map))continue;
            long timestamp=lastRenderTime(stored.getLastModified(),history.lastSuccessful(TileRefreshHistory.namespace(stored),stored.x,stored.y),System.currentTimeMillis());
            if(!stale(timestamp,System.currentTimeMillis(),maxAgeMillis)||!stored.exists())continue;
            awaiting.put(candidate.render,new Attempt(candidate));submitted.incrementAndGet();manager.tileQueue.push(candidate.render);break;
        }
    }catch(Exception failure){errorState="background/history error";closed=true;saveCheckpoint();if(scan!=null)scan.cancel(true);scanner.shutdownNow();worker.shutdown();Log.warning("Stale tile refresh stopped after a background/history error; normal rendering remains enabled");}finally{pendingCount=pending.size();}}
    @Override public void close(){closed=true;scanner.shutdownNow();worker.shutdown();try{if(!worker.awaitTermination(5,TimeUnit.SECONDS))worker.shutdownNow();saveCheckpoint();history.flush();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(IOException failure){Log.warning("Could not save stale tile refresh dates");}}
}
