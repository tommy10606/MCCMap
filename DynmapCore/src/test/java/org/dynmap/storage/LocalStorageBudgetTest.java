package org.dynmap.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.dynmap.*;
import org.dynmap.storage.filetree.FileTreeMapStorage;
import org.dynmap.utils.BufferOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalStorageBudgetTest {
    @TempDir Path root;
    @Test void capRefusalKeepsExistingTileAndQueuesItsUpdateForRetry() throws Exception {
        Path tiles=Files.createDirectories(root.resolve("web/tiles"));
        var activeMonitor=new java.util.concurrent.atomic.AtomicReference<StorageUsageMonitor>();
        DynmapCore core=new DynmapCore(){@Override public StorageUsageMonitor getStorageUsage(){return activeMonitor.get();}};
        core.configuration=new ConfigurationNode();core.setDataFolder(root.toFile());
        var tileDirectory=DynmapCore.class.getDeclaredField("tilesDirectory");tileDirectory.setAccessible(true);tileDirectory.set(core,tiles.toFile());
        DynmapWorld world=mock(DynmapWorld.class);when(world.getName()).thenReturn("world");
        MapType map=mock(MapType.class);when(map.getName()).thenReturn("flat");when(map.getPrefix()).thenReturn("flat");
        when(map.getImageFormat()).thenReturn(MapType.ImageFormat.FORMAT_PNG);
        MapTypeState state=mock(MapTypeState.class);when(world.getMapState(map)).thenReturn(state);
        var storage=new FileTreeMapStorage();assertTrue(storage.init(core));
        var tile=storage.getTile(world,map,0,0,0,MapType.ImageVariant.STANDARD);
        var old=new BufferOutputStream();old.write(new byte[]{1,2,3,4});assertTrue(tile.write(1,old,1000));
        Path image=tiles.resolve("world/flat/0_0/0_0.png");assertArrayEquals(new byte[]{1,2,3,4},Files.readAllBytes(image));
        long used;try(var files=Files.walk(root)){used=files.filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(Exception e){throw new RuntimeException(e);}}).sum();}
        try(var monitor=new StorageUsageMonitor(List.of(root),used+2,60)){
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(monitor.snapshot().measuredAt()==0&&System.nanoTime()<deadline)Thread.sleep(5);
            assertTrue(monitor.snapshot().measuredAt()>0);activeMonitor.set(monitor);
            var larger=new BufferOutputStream();larger.write(new byte[100]);assertFalse(tile.write(2,larger,2000));
            assertArrayEquals(new byte[]{1,2,3,4},Files.readAllBytes(image));verify(state).invalidateTile(0,0);
            assertFalse(Files.exists(image.resolveSibling("0_0.png.new")));
            assertTrue(storage.setMarkerFile("world", "{\"markers\":{}}"));
            assertEquals("{\"markers\":{}}", storage.getMarkerFile("world"));
        }
    }
}
