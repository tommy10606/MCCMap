package org.dynmap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.dynmap.hdmap.*;
import org.dynmap.storage.filetree.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
class StaleCheckpointStartupTest {
 @TempDir Path root;
 @Test void savedScanWaitsForWorldActivationBeforeRestoringOrOverwritingIt()throws Exception {
  MapManager previous=MapManager.mapman;var manager=mock(MapManager.class);MapManager.mapman=manager;
  try {
   var core=new DynmapCore();core.configuration=new ConfigurationNode();core.setDataFolder(root.toFile());Files.createDirectory(root.resolve("web"));var web=DynmapCore.class.getDeclaredField("webpath");web.setAccessible(true);web.set(core,"web");
   core.configuration.put("stale-tile-refresh",Map.of("startup-delay-seconds",0));
   var directory=DynmapCore.class.getDeclaredField("tilesDirectory");directory.setAccessible(true);directory.set(core,Files.createDirectory(root.resolve("tiles")).toFile());
   var worlds=new CopyOnWriteArrayList<DynmapWorld>();when(manager.getWorlds()).thenReturn(worlds);
   var world=mock(DynmapWorld.class,withSettings().useConstructor("world",256,64).defaultAnswer(CALLS_REAL_METHODS));
   var map=mock(HDMap.class);when(map.getName()).thenReturn("flat");when(map.getPrefix()).thenReturn("flat_rnew");when(map.getImageFormat()).thenReturn(MapType.ImageFormat.FORMAT_PNG);when(map.getVariants()).thenReturn(new MapType.ImageVariant[]{MapType.ImageVariant.STANDARD});var perspective=HDMap.class.getDeclaredField("perspective");perspective.setAccessible(true);perspective.set(map,mock(HDPerspective.class));world.maps.add(map);
   var storage=new FileTreeMapStorage();assertTrue(storage.init(core));doReturn(storage).when(world).getMapStorage();when(manager.getWorld("world")).thenReturn(world);
   var store=new ProgressCheckpoint(core,"stale-tile-scan");var saved=new ConfigurationNode();saved.put("layout","[world:flat:flat_rnew]");saved.put("active",true);saved.put("scope",0);saved.put("cursor",new TileScanCursor().save());saved.put("pending",List.of(Map.of("world","world","map","flat","layout","flat_rnew","variant","","x",0,"y",0,"timestamp",1)));store.write(saved);
   try(var refresher=new StaleTileRefresh(core,manager)) {
    Thread.sleep(250);assertEquals("[world:flat:flat_rnew]",store.read().getString("layout"));
    worlds.add(world);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
    while(System.nanoTime()<deadline&&refresher.describe().stream().noneMatch(line->line.contains("Pending: 1;")))Thread.sleep(20);
    assertTrue(refresher.describe().stream().anyMatch(line->line.contains("Pending: 1;")));
   }
  }finally{MapManager.mapman=previous;}
 }
}
