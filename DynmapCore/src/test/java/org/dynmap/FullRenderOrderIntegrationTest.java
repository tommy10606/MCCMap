package org.dynmap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.dynmap.common.*;
import org.dynmap.hdmap.*;
import org.dynmap.storage.*;
import org.dynmap.utils.*;
import org.junit.jupiter.api.Test;

class FullRenderOrderIntegrationTest {
    private static void set(Object object,String name,Object value)throws Exception {var f=MapManager.class.getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    @Test void productionFullRenderFillsEveryMapsHolesBeforeRefreshingOldTilesAcrossRepeatedRestarts() throws Exception {
        var previous=MapManager.mapman;
        try {
            var manager=mock(MapManager.class,CALLS_REAL_METHODS);MapManager.mapman=manager;
            var core=new DynmapCore();core.configuration=new ConfigurationNode();set(manager,"core",core);
            set(manager,"parallelrendercnt",1);set(manager,"fullRenderOfflinePercent",100.0);set(manager,"fullRenderOnlinePercent",25.0);set(manager,"fullRenderOnlineWorkers",1);set(manager,"progressinterval",100);
            var active=new HashMap<String,Object>();set(manager,"active_renders",active);
            manager.tileQueue=mock(AsynchronousQueue.class);manager.chunk_caches_created=new AtomicInteger();
            manager.chunks_read=new AtomicInteger[MapChunkCache.ChunkStats.values().length];manager.chunks_read_times=new AtomicLong[manager.chunks_read.length];
            for(int i=0;i<manager.chunks_read.length;i++){manager.chunks_read[i]=new AtomicInteger();manager.chunks_read_times[i]=new AtomicLong();}
            manager.hdmapman=mock(HDMapManager.class,withSettings().useConstructor());
            var server=mock(DynmapServerInterface.class);core.setServer(server);
            var world=mock(DynmapWorld.class,withSettings().useConstructor("world",320,63,-64).defaultAnswer(CALLS_REAL_METHODS));
            doReturn(world).when(manager).getWorld("world");doReturn(true).when(world).isLoaded();doReturn(new DynmapLocation("world",0,64,0)).when(world).getSpawnLocation();
            var store=mock(MapStorage.class);doReturn(store).when(world).getMapStorage();
            var events=new ArrayList<String>();var exists=new HashSet<String>(Set.of("flat:0","surface:0"));
            var cache=mock(MapChunkCache.class);when(server.createMapChunkCache(eq(world),anyList(),anyBoolean(),anyBoolean(),anyBoolean(),anyBoolean())).thenReturn(cache);
            doAnswer(call->{((Runnable)call.getArgument(0)).run();return null;}).when(server).scheduleServerTask(any(Runnable.class),eq(0L));
            when(server.getRenderCheckSnapshot(world)).thenReturn(new MissingTileCheck.Snapshot(null,List.of(new DynmapChunk(0,0),new DynmapChunk(1,0)),null));
            for(String name:List.of("flat","surface")) {
                var perspective=mock(HDPerspective.class);when(perspective.getName()).thenReturn(name);manager.hdmapman.perspectives.put(name,perspective);
                when(perspective.getRequiredChunks(any())).thenReturn(List.of(new DynmapChunk(0,0)));
                when(perspective.render(eq(cache),any(HDMapTile.class),isNull())).thenAnswer(call->{var tile=(HDMapTile)call.getArgument(1);events.add(name+":"+tile.tx);exists.add(name+":"+tile.tx);return true;});
                var map=mock(HDMap.class);when(map.getName()).thenReturn(name);when(map.getPrefix()).thenReturn(name);var perspectiveField=HDMap.class.getDeclaredField("perspective");perspectiveField.setAccessible(true);perspectiveField.set(map,perspective);when(map.getVariants()).thenReturn(new MapType.ImageVariant[]{MapType.ImageVariant.STANDARD});
                when(map.getMapNamesSharingRender(world)).thenReturn(List.of(name));when(map.getMapsSharingRender(world)).thenReturn(List.of(map));
                when(map.getTiles(eq(world),anyInt(),anyInt(),anyInt())).thenReturn(List.of(new HDMapTile(world,perspective,0,0,0,0)));
                when(map.getTileCoords(eq(world),anyInt(),anyInt(),anyInt(),anyInt(),anyInt(),anyInt())).thenAnswer(call->List.of(new TileFlags.TileCoord((int)call.getArgument(1)/16,0)));
                doAnswer(call->{var flags=(TileFlags)call.getArgument(1);assertTrue(flags.getFlag(0,0));assertTrue(flags.getFlag(1,0));assertTrue(events.size()>=3 && events.contains("flat:1") && events.contains("surface:1"),"cleanup must wait until the refresh pass");return null;}).when(map).purgeOldTiles(eq(world),any(TileFlags.class),anyLong());
                when(store.getTile(eq(world),eq(map),anyInt(),anyInt(),eq(0),eq(MapType.ImageVariant.STANDARD))).thenAnswer(call->{int x=call.getArgument(2);var tile=mock(MapStorageTile.class);when(tile.exists()).thenAnswer(c->exists.contains(name+":"+x));return tile;});
                world.maps.add(map);
            }
            Class<?> type=Arrays.stream(MapManager.class.getDeclaredClasses()).filter(c->c.getSimpleName().equals("FullWorldRenderState")).findFirst().orElseThrow();
            var create=type.getDeclaredConstructor(MapManager.class,DynmapWorld.class,DynmapLocation.class,DynmapCommandSender.class,String.class,boolean.class,boolean.class);create.setAccessible(true);
            var restore=type.getDeclaredConstructor(MapManager.class,ConfigurationNode.class);restore.setAccessible(true);
            var save=type.getDeclaredMethod("saveState");save.setAccessible(true);
            Object job=create.newInstance(manager,world,new DynmapLocation("world",0,64,0),mock(DynmapCommandSender.class),null,false,false);active.put("world",job);
            for(int step=0;step<100&&!active.isEmpty();step++) {
                ((Runnable)job).run();
                // Let an asynchronously requested server snapshot reach its consumer.
                // Region/projection cursor restart behavior is tested independently.
                if(!active.isEmpty())((Runnable)job).run();
                if(!active.isEmpty()) {job=restore.newInstance(manager,new ConfigurationNode((Map<String,Object>)save.invoke(job)));active.put("world",job);}
            }
            assertTrue(active.isEmpty(),"full render must finish: "+events+" "+save.invoke(job));assertEquals(List.of("flat:1","surface:1","flat:0","surface:0"),events);
            for(var map:world.maps){verify(map,times(1)).purgeOldTiles(eq(world),any(TileFlags.class),anyLong());verify(map,never()).getAdjecentTiles(any());}
        } finally {MapManager.mapman=previous;}
    }
}
