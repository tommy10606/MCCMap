package org.dynmap;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.dynmap.hdmap.HDMap;
import org.dynmap.storage.filetree.*;

/** Low-duty cleanup can reclaim space during manual rendering, including a storage pause. */
final class LegacyTileCleanup implements AutoCloseable {
    private record Scope(DynmapWorld world,HDMap map,FileTreeMapStorage storage) {
        String key(){return world.getName()+":"+map.getName()+":"+map.getPrefix()+":"+map.getTilePreviewSources();}
    }
    private final DynmapCore core;
    private final MapManager manager;
    private final ProgressCheckpoint checkpoint;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(task->{var t=new Thread(task,"MCCMap legacy tile cleanup");t.setDaemon(true);return t;});
    private final int budget;
    private final boolean enabled;
    private volatile boolean closed;
    private String layout="",state="starting";
    private int scope;
    private long nextRun,nextSave,examined,deleted,bytes,retained;
    private LegacyCleanupCursor cursor=new LegacyCleanupCursor();
    LegacyTileCleanup(DynmapCore core,MapManager manager) {this(core,manager,true);}
    LegacyTileCleanup(DynmapCore core,MapManager manager,boolean schedule) {
        this.core=core;this.manager=manager;enabled=core.configuration.getBoolean("legacy-tile-cleanup/enabled",true);
        budget=Math.max(1,Math.min(512,core.configuration.getInteger("legacy-tile-cleanup/checks-per-batch",64)));
        checkpoint=ProgressCheckpoint.optional(core,"legacy-tile-cleanup");
        nextRun=System.currentTimeMillis()+Math.max(0,core.configuration.getInteger("legacy-tile-cleanup/startup-delay-seconds",60))*1000L;
        if(checkpoint!=null)try {
            var saved=checkpoint.read();
            if(saved!=null){layout=saved.getString("layout","");scope=saved.getInteger("scope",0);if(scope<0||scope>100000)throw new IOException("Invalid cleanup scope");
                nextRun=Math.max(nextRun,Long.parseLong(saved.getString("next-run","0")));examined=Math.max(0,Long.parseLong(saved.getString("examined","0")));deleted=Math.max(0,Long.parseLong(saved.getString("deleted","0")));
                bytes=Math.max(0,Long.parseLong(saved.getString("bytes","0")));retained=Math.max(0,Long.parseLong(saved.getString("retained","0")));var savedCursor=saved.getNode("cursor");if(savedCursor!=null)cursor=new LegacyCleanupCursor(savedCursor);}
        }catch(IOException|RuntimeException invalid){scope=0;layout="";cursor=new LegacyCleanupCursor();Log.warning("Legacy cleanup checkpoint invalid; restarting its safe scan");}
        if(!enabled)state="disabled";
        else if(schedule) worker.scheduleWithFixedDelay(this::tick,1,250,TimeUnit.MILLISECONDS);
    }
    private List<Scope> scopes() {
        var result=new ArrayList<Scope>();
        for(var world:manager.getWorlds())if(world.isLoaded()&&world.getMapStorage() instanceof FileTreeMapStorage storage)
            for(var map:world.maps)if(map instanceof HDMap hd&&!map.isReadOnly()&&!map.getTilePreviewSources().isEmpty())result.add(new Scope(world,hd,storage));
        result.sort(Comparator.comparing(Scope::key));return result;
    }
    synchronized void tick() {
        if(closed||!enabled)return;
        try {
            if(System.currentTimeMillis()<nextRun){state="waiting for next pass";return;}
            String pause=manager.legacyCleanupPauseReason();if(pause!=null){state="paused: "+pause;return;}
            var scopes=scopes();String current=scopes.stream().map(Scope::key).reduce("",(a,b)->a+"\n"+b);
            if(!current.equals(layout)){accumulate();layout=current;scope=0;}
            if(scope>=scopes.size()) {
                scope=0;nextRun=System.currentTimeMillis()+TimeUnit.HOURS.toMillis(24);state="idle; pass complete";save();
                var usage=core.getStorageUsage();if(usage!=null)usage.requestScan();return;
            }
            var selected=scopes.get(scope);state=selected.world.getName()+":"+selected.map.getName()+" ("+(scope+1)+"/"+scopes.size()+")";
            if(selected.storage.cleanupLegacyStep(selected.world,selected.map,cursor,budget)) {
                accumulate();scope++;
            }
            if(System.currentTimeMillis()>=nextSave)save();
        }catch(java.io.InterruptedIOException stopped){Thread.currentThread().interrupt();}
        catch(IOException|RuntimeException failure){if(!closed){state="stopped after error; see console";closed=true;Log.warning("Legacy tile cleanup stopped safely; normal rendering remains enabled",failure);worker.shutdown();}}
    }
    private void accumulate(){examined+=cursor.examined;deleted+=cursor.deleted;bytes+=cursor.bytes;retained+=cursor.retained;cursor=new LegacyCleanupCursor();}
    private void save() {
        if(checkpoint==null)return;
        try {
            var saved=new ConfigurationNode();saved.put("layout",layout);saved.put("scope",scope);saved.put("cursor",cursor.save());saved.put("next-run",nextRun);
            saved.put("examined",examined);saved.put("deleted",deleted);saved.put("bytes",bytes);saved.put("retained",retained);checkpoint.write(saved);
            nextSave=System.currentTimeMillis()+checkpoint.intervalMillis();
        }catch(IOException failure){nextSave=System.currentTimeMillis()+60000;Log.warning("Could not save legacy tile cleanup progress; next boot will safely replay recent work");}
    }
    synchronized String describe(){return "Legacy tile cleanup: "+state+"; checked="+(examined+cursor.examined)+", removed="+(deleted+cursor.deleted)
        +", reclaimed="+StorageUsageMonitor.bytes(bytes+cursor.bytes)+", retained candidates="+(retained+cursor.retained);}
    @Override public void close(){closed=true;worker.shutdownNow();try{worker.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}synchronized(this){save();}}
}
