package org.dynmap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.dynmap.storage.filetree.FileTreeMapStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class TileEnumerationSafetyTest {
 @TempDir Path root;
 @Test void excludesSymlinkCyclesAndExternalTileDirectories()throws Exception {
  Path tiles=Files.createDirectories(root.resolve("tiles/world/flat/0_0"));Files.write(tiles.resolve("0_0.png"),new byte[]{1});
  Path external=Files.createDirectories(root.resolve("external"));Files.write(external.resolve("1_1.png"),new byte[]{2});
  Files.createSymbolicLink(tiles.getParent().resolve("loop"),tiles.getParent());Files.createSymbolicLink(tiles.getParent().resolve("outside"),external);
  DynmapCore core=new DynmapCore();core.configuration=new ConfigurationNode();core.setDataFolder(root.toFile());
  var field=DynmapCore.class.getDeclaredField("tilesDirectory");field.setAccessible(true);field.set(core,root.resolve("tiles").toFile());
  var storage=new FileTreeMapStorage();assertTrue(storage.init(core));var world=mock(DynmapWorld.class);when(world.getName()).thenReturn("world");
  var map=mock(MapType.class);when(map.getPrefix()).thenReturn("flat");when(map.getImageFormat()).thenReturn(MapType.ImageFormat.FORMAT_PNG);
  when(map.getVariants()).thenReturn(new MapType.ImageVariant[]{MapType.ImageVariant.STANDARD});List<Integer> found=new ArrayList<>();
  assertTimeoutPreemptively(Duration.ofSeconds(2),()->storage.enumMapTiles(world,map,(tile,format)->found.add(tile.x)));
  assertEquals(List.of(0),found);
 }
}
