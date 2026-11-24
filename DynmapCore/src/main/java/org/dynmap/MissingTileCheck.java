package org.dynmap;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.dynmap.hdmap.*;
import org.dynmap.utils.*;

/** Restartable region-header, projection and missing-tile phases after full renders. */
public final class MissingTileCheck implements AutoCloseable {
    public record Snapshot(Path regionDirectory,List<DynmapChunk> loaded,Polygon border) {}
    private final DynmapCore core;private final MapManager manager;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"MCCMap missing tiles");t.setDaemon(true);return t;});
    private final Set<String> pending=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;private volatile long examined,queued,zoomQueued;private volatile String state="idle";
    MissingTileCheck(DynmapCore core,MapManager manager){this.core=core;this.manager=manager;}
    void restore(){if(!ProgressCheckpoint.enabled(core.configuration))return;for(var w:manager.getWorlds())restoreWorld(w);}
    void restoreWorld(DynmapWorld w){if(closed||!ProgressCheckpoint.enabled(core.configuration))return;for(var m:w.maps)if(m instanceof HDMap map)try{var store=new ProgressCheckpoint(core,key(w,map));var saved=store.read();if(saved!=null)start(w,map,saved);}catch(IOException failure){Log.warning("Missing-tile checkpoint unavailable; normal rendering remains enabled");}}
    private static String key(DynmapWorld world,HDMap map){return "missing:"+world.getName()+":"+map.getName();}
    public List<String> describe(){return List.of("Missing tile check: "+state,"  Examined: "+examined+"; repair jobs queued: "+queued+"; zoom repairs: "+zoomQueued);}
    void afterFullRender(DynmapWorld world,List<HDMap> maps){for(var map:maps)start(world,map,null);}
    private void start(DynmapWorld world,HDMap map,ConfigurationNode restored){
        String id=key(world,map);if(closed||map.isReadOnly()||!pending.add(id))return;
        ProgressCheckpoint store=null;
        if(ProgressCheckpoint.enabled(core.configuration))try{store=new ProgressCheckpoint(core,id);if(restored==null){var request=new ConfigurationNode();request.put("layout",map.getPrefix());request.put("phase","init");store.write(request);}}catch(IOException failure){Log.warning("Could not save missing-tile scan request");}
        var checkpoint=store;
        core.getServer().scheduleServerTask(()->{if(closed||manager.getWorld(world.getName())!=world||!world.isLoaded()){pending.remove(id);return;}try{var snapshot=core.getServer().getRenderCheckSnapshot(world);worker.execute(()->{try{new Job(world,map,snapshot,checkpoint,restored).run();}finally{pending.remove(id);}});}catch(RuntimeException stopped){pending.remove(id);}},0);
    }
    private boolean ready(DynmapWorld world)throws InterruptedException{
        while(!closed&&!Thread.currentThread().isInterrupted()){if(manager.getWorld(world.getName())!=world||!world.isLoaded())return false;if(manager.canRefreshStaleTiles(128))return true;state="waiting for render headroom";Thread.sleep(1000);}throw new InterruptedException();
    }
    private final class Job {
        final DynmapWorld world;final HDMap map;final Snapshot snapshot;final ProgressCheckpoint store;
        String phase="init";TileFlags chunks=new TileFlags(),expected=new TileFlags();ScanCursor regions=new ScanCursor();long nextSave;
        Job(DynmapWorld world,HDMap map,Snapshot snapshot,ProgressCheckpoint store,ConfigurationNode saved){this.world=world;this.map=map;this.snapshot=snapshot;this.store=store;
            if(saved!=null&&map.getPrefix().equals(saved.getString("layout",""))&&borderKey(snapshot.border()).equals(saved.getString("border",borderKey(snapshot.border()))))try{phase=saved.getString("phase","init");if(!List.of("init","regions","project","tiles").contains(phase))throw new IllegalArgumentException("Invalid scan phase");chunks.load(saved.getStrings("chunks",List.of()));expected.load(saved.getStrings("expected",List.of()));var cursor=saved.getNode("regions");if(cursor!=null)regions=new ScanCursor(cursor);}catch(IOException|RuntimeException invalid){phase="init";chunks.clear();expected.clear();regions=new ScanCursor();}
        }
        void save(){if(store==null)return;try{if(!manager.checkpointWorld(world))throw new IOException("Repair queue checkpoint failed");var n=new ConfigurationNode();n.put("layout",map.getPrefix());n.put("border",borderKey(snapshot.border()));n.put("phase",phase);n.put("chunks",chunks.save());n.put("expected",expected.save());n.put("regions",regions.save());store.write(n);nextSave=System.currentTimeMillis()+ProgressCheckpoint.intervalMillis(core.configuration);}catch(IOException failure){Log.warning("Could not save missing-tile progress; previous checkpoint retained");}}
        void maybeSave(){if(System.currentTimeMillis()>=nextSave)save();}
        void run(){boolean complete=false;try{
            if(!ready(world))return;state="scanning "+world.getName()+":"+map.getName();
            if(phase.equals("init")){for(var c:snapshot.loaded())if(inside(snapshot.border(),c.x,c.z))chunks.setFlag(c.x,c.z,true);phase="regions";save();}
            if(phase.equals("regions")){
                Path file;while(snapshot.regionDirectory()!=null&&(file=regions.next(snapshot.regionDirectory()))!=null){if(!ready(world))return;if(file.getParent().equals(snapshot.regionDirectory().toAbsolutePath().normalize()))readRegionFile(file,snapshot.border(),chunks);regions.acknowledge();maybeSave();}
                phase="project";save();
            }
            if(phase.equals("project")){
                var iterator=chunks.getIterator();var coord=new TileFlags.TileCoord();int remaining=chunks.countFlags(),batch=0;
                while(remaining-->0&&iterator.next(coord)){if((batch++&127)==0&&!ready(world))return;for(var t:map.getTileCoords(world,coord.x*16,world.minY,coord.y*16,coord.x*16+15,world.worldheight-1,coord.y*16+15))expected.setFlag(t.x,t.y,true);chunks.setFlag(coord.x,coord.y,false);maybeSave();}
                chunks.clear();phase="tiles";save();
            }
            var iterator=expected.getIterator();var coord=new TileFlags.TileCoord();int remaining=expected.countFlags();
            while(remaining-->0&&iterator.next(coord)){
                if(!ready(world))return;state="checking "+world.getName()+":"+map.getName();examined++;boolean missing=false;
                for(var variant:map.getVariants()){var tile=world.getMapStorage().getTile(world,map,coord.x,coord.y,0,variant);try{if(!tile.exists())missing=true;else zoomQueued+=repairZoomChain(tile,world.getExtraZoomOutLevels()+map.getMapZoomOutLevels());}finally{tile.cleanup();}}
                if(missing&&manager.tileQueue.push(new HDMapTile(world,map.getPerspective(),coord.x,coord.y,map.getBoostZoom(),map.getTileScale())))queued++;
                expected.setFlag(coord.x,coord.y,false);maybeSave();
            }
            if(!manager.checkpointWorld(world))throw new IOException("Repair queue checkpoint failed");if(store!=null)store.delete();complete=true;state="complete";
            Log.info("Missing tile check for '"+world.getName()+"': checked "+examined+" tiles; queued "+queued+" repair jobs and "+zoomQueued+" zoom repairs.");
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();state="stopped";}catch(IOException|RuntimeException failure){state="failed; see console";Log.warning("Missing tile check failed; normal rendering remains enabled");}finally{if(!complete)save();}}
    }
    private static String borderKey(Polygon border){if(border==null)return "none";var points=new ArrayList<String>();for(int i=0;i<border.size();i++){var p=border.getVertex(i);points.add(p.x+":"+p.y);}return points.toString();}
    static void readRegionFile(Path path,Polygon border,TileFlags chunks)throws IOException {
        var match=java.util.regex.Pattern.compile("r[.](-?[0-9]+)[.](-?[0-9]+)[.]mca").matcher(path.getFileName().toString());
        if(!match.matches()||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))return;
        long rx,rz;try{rx=Math.multiplyExact(Long.parseLong(match.group(1)),32);rz=Math.multiplyExact(Long.parseLong(match.group(2)),32);}catch(NumberFormatException|ArithmeticException invalid){return;}
        if(Math.abs(rx)>1_875_000||Math.abs(rz)>1_875_000)return;
        if(border!=null){var overlap=border.clip(rx*16,rz*16,(rx+32)*16,(rz+32)*16);if(overlap==null||overlap.size()<3)return;}
        try(var channel=java.nio.channels.FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            var header=java.nio.ByteBuffer.allocate(4096);while(header.hasRemaining()&&channel.read(header)>0){}if(header.hasRemaining())return;header.flip();long size=channel.size();
            for(int i=0;i<1024;i++){int location=header.getInt(),sector=location>>>8,count=location&255,x=(int)rx+(i&31),z=(int)rz+(i>>5);if(sector>=2&&count>0&&(long)sector*4096<size&&inside(border,x,z))chunks.setFlag(x,z,true);}
        }
    }
    static boolean inside(Polygon border,int cx,int cz) {
        if(border==null)return true;
        Polygon clipped=border.clip((double)cx*16,(double)cz*16,(double)cx*16+16,(double)cz*16+16);
        if(clipped==null)return false;
        double area=0;
        for(int i=0;i<clipped.size();i++) {
            var a=clipped.getVertex(i);var b=clipped.getVertex((i+1)%clipped.size());area+=a.x*b.y-b.x*a.y;
        }
        return Math.abs(area)>0.000001;
    }
    static void readRegionHeaders(Path directory, Polygon border, TileFlags chunks) throws IOException {
        if(directory==null||Files.isSymbolicLink(directory)||!Files.isDirectory(directory))return;
        var pattern=java.util.regex.Pattern.compile("r[.](-?[0-9]+)[.](-?[0-9]+)[.]mca");
        try(var files=Files.newDirectoryStream(directory,"*.mca")) {
            for(Path path:files) {
                if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();
                var match=pattern.matcher(path.getFileName().toString());
                if(!match.matches()||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))continue;
                long rx,rz;
                try{rx=Math.multiplyExact(Long.parseLong(match.group(1)),32);rz=Math.multiplyExact(Long.parseLong(match.group(2)),32);}catch(NumberFormatException|ArithmeticException invalid){continue;}
                if(Math.abs(rx)>1_875_000||Math.abs(rz)>1_875_000)continue;
                if(border!=null){
                    Polygon overlap=border.clip(rx*16,rz*16,(rx+32)*16,(rz+32)*16);
                    if(overlap==null||overlap.size()<3)continue;
                }
                try(var channel=java.nio.channels.FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                    var header=java.nio.ByteBuffer.allocate(4096);while(header.hasRemaining()&&channel.read(header)>0){}
                    if(header.hasRemaining())continue;header.flip();
                    long fileSize=channel.size();
                    for(int i=0;i<1024;i++) {
                        int location=header.getInt(),sector=location>>>8,count=location&255;
                        int x=(int)rx+(i&31),z=(int)rz+(i>>5);
                        if(sector>=2&&count>0&&(long)sector*4096<fileSize&&inside(border,x,z))chunks.setFlag(x,z,true);
                    }
                }
            }
        }
    }
    static int repairZoomChain(org.dynmap.storage.MapStorageTile base,int levels) {
        var child=base;
        try {
            for(int level=0;level<levels;level++) {
                var parent=child.getZoomOutTile();
                if(parent==null)return 0;
                boolean exists;
                try {exists=parent.exists();}catch(RuntimeException failure){parent.cleanup();throw failure;}
                if(!exists){parent.cleanup();child.enqueueZoomOutUpdate();return 1;}
                if(child!=base)child.cleanup();
                child=parent;
            }
            return 0;
        }finally{if(child!=base)child.cleanup();}
    }
    @Override public void close(){closed=true;worker.shutdownNow();try{worker.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
}
