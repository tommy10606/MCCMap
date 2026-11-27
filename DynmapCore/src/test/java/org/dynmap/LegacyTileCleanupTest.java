package org.dynmap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.dynmap.hdmap.HDMap;
import org.dynmap.storage.filetree.FileTreeMapStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyTileCleanupTest {
    @TempDir Path root;
    @Test void workerCheckpointsAndResumesAnUnacknowledgedFileWithoutQuietingErrorsOrLosingCounters()throws Exception {
        var core=new DynmapCore();core.configuration=new ConfigurationNode();core.setDataFolder(root.toFile());
        core.configuration.put("legacy-tile-cleanup",new LinkedHashMap<>(Map.of("startup-delay-seconds",0,"checks-per-batch",1)));
        var web=DynmapCore.class.getDeclaredField("webpath");web.setAccessible(true);web.set(core,"web");
        var tiles=DynmapCore.class.getDeclaredField("tilesDirectory");tiles.setAccessible(true);tiles.set(core,Files.createDirectories(root.resolve("tiles")).toFile());
        var manager=mock(MapManager.class);var world=mock(DynmapWorld.class,withSettings().useConstructor("world",256,64).defaultAnswer(CALLS_REAL_METHODS));when(world.isLoaded()).thenReturn(true);
        var map=mock(HDMap.class);when(map.getName()).thenReturn("flat");when(map.getPrefix()).thenReturn("flat_new");when(map.getImageFormat()).thenReturn(MapType.ImageFormat.FORMAT_PNG);when(map.getVariants()).thenReturn(new MapType.ImageVariant[]{MapType.ImageVariant.STANDARD});
        when(map.getTilePreviewSources()).thenReturn(List.of(new MapType.TilePreviewSource("flat_old",0,MapType.ImageEncoding.PNG)));world.maps.add(map);
        var storage=new FileTreeMapStorage();assertTrue(storage.init(core));when(world.getMapStorage()).thenReturn(storage);when(manager.getWorlds()).thenReturn(List.of(world));
        for(String prefix:List.of("flat_new","flat_old"))for(int x=0;x<3;x++) {
            var p=root.resolve("tiles/world/"+prefix+"/0_0/"+x+"_0.png");Files.createDirectories(p.getParent());
            var image=new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,0xff0000ff);assertTrue(ImageIO.write(image,"png",p.toFile()));image.flush();
        }
        try(var first=new LegacyTileCleanup(core,manager,false)){first.tick();assertTrue(first.describe().contains("removed=0"));}
        core.configuration.getNode("legacy-tile-cleanup").put("checks-per-batch",64);
        try(var resumed=new LegacyTileCleanup(core,manager,false)) {
            when(manager.legacyCleanupPauseReason()).thenReturn("low TPS");resumed.tick();assertTrue(resumed.describe().contains("low TPS"));
            when(manager.legacyCleanupPauseReason()).thenReturn(null);
            for(int i=0;i<100&&!resumed.describe().contains("removed=3");i++)resumed.tick();
            assertTrue(resumed.describe().contains("removed=3"),resumed.describe());
        }
        for(int x=0;x<3;x++){assertFalse(Files.exists(root.resolve("tiles/world/flat_old/0_0/"+x+"_0.png")));assertTrue(Files.exists(root.resolve("tiles/world/flat_new/0_0/"+x+"_0.png")));}
    }
}
