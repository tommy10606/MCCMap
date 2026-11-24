package org.dynmap.hdmap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.dynmap.*;
import org.junit.jupiter.api.Test;

class VolumeProjectionTest {
    @Test void chunkVolumesIncludeTheirBlocksAtEverySupportedTileScale() {
        var settings = new ConfigurationNode(); settings.put("name", "projection-check");
        settings.put("scale", 16); settings.put("azimuth", 135); settings.put("inclination", 30);
        var perspective = new IsoHDPerspective(new DynmapCore(), settings);
        var world = mock(DynmapWorld.class);
        for (int scale = 0; scale <= 4; scale++) for (int x : new int[]{-8192, -17, 4096}) for (int z : new int[]{-4096, 8192}) {
            var block = perspective.getTileCoords(world, x, 63, z, scale);
            var volume = perspective.getTileCoords(world, x, 63, z, x + 15, 78, z + 15, scale);
            assertTrue(volume.containsAll(block), "Volume misses block tiles at scale " + scale + ", " + x + "," + z);
        }
    }
}
