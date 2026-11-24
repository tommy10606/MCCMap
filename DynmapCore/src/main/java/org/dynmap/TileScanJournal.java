package org.dynmap;
import java.io.IOException;
import java.util.function.*;
import org.dynmap.storage.MapStorageTile;
import org.dynmap.storage.filetree.*;

/** Resume a metadata scan only after its accumulated side effects have been checkpointed too. */
public final class TileScanJournal {
    private static void commit(Runnable durable)throws IOException{try{durable.run();}catch(RuntimeException failure){throw new IOException("Could not checkpoint scan effects",failure);}}
    public static boolean run(DynmapWorld world,MapType map,String purpose,Consumer<MapStorageTile> visitor,
        Supplier<ConfigurationNode> effects,Consumer<ConfigurationNode> restore,Runnable durable)throws IOException {
        DynmapCore core=MapManager.mapman==null?null:MapManager.mapman.getCoreForCheckpoints();
        ProgressCheckpoint store=core==null?null:ProgressCheckpoint.optional(core,purpose+":"+world.getName()+":"+map.getName());
        var cursor=new TileScanCursor();boolean completed=false;long next=System.currentTimeMillis()+ (core==null?60000:ProgressCheckpoint.intervalMillis(core.configuration));
        String layout=map.getPrefix()+":"+world.minY+":"+world.worldheight;
        if(store!=null)try{var saved=store.read();if(saved!=null&&layout.equals(saved.getString("layout",""))){cursor=new TileScanCursor(saved.getNode("cursor"));var old=saved.getNode("effects");if(old!=null)restore.accept(old);}}
        catch(IOException|RuntimeException invalid){Log.warning("Tile-scan checkpoint invalid; restarting this scan safely");}
        try {
            while(true){
                if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();
                boolean done=((FileTreeMapStorage)world.getMapStorage()).scanTiles(world,map,cursor,4096,visitor);
                if(done){if(store!=null){commit(durable);store.delete();}completed=true;return true;}
                if(store!=null&&System.currentTimeMillis()>=next){commit(durable);var n=new ConfigurationNode();n.put("layout",layout);n.put("cursor",cursor.save());n.put("effects",effects.get());store.write(n);next=System.currentTimeMillis()+ProgressCheckpoint.intervalMillis(core.configuration);}
            }
        }finally{if(!completed&&store!=null){try{commit(durable);}catch(IOException shutdown){/* The owner also saves final queues after its workers stop. */}var n=new ConfigurationNode();n.put("layout",layout);n.put("cursor",cursor.save());n.put("effects",effects.get());store.write(n);}}
    }
}
