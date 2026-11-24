package org.dynmap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import org.dynmap.hdmap.HDMap;
import org.dynmap.utils.TileFlags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RenderInventoryTest {
    @TempDir Path root;
    private HDMap map() {
        var map = mock(HDMap.class);
        when(map.getTileCoords(any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
            .thenAnswer(call -> List.of(new TileFlags.TileCoord((int)call.getArgument(1) / 16, (int)call.getArgument(3) / 16)));
        return map;
    }
    @Test void partiallyProjectedInventorySurvivesRestartWithoutDoubleCounting() throws Exception {
        var chunks = new ArrayList<DynmapChunk>();
        for (int i = 0; i < 2500; i++) chunks.add(new DynmapChunk(i, -4));
        var snapshot = new MissingTileCheck.Snapshot(null, chunks, null);
        var world = mock(DynmapWorld.class);
        var map = map();
        var inventory = new RenderInventory();
        assertFalse(inventory.step(world, map, snapshot, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertEquals("project", inventory.phase());
        var restored = new RenderInventory(inventory.save());
        assertFalse(restored.step(world, map, snapshot, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertTrue(restored.step(world, map, snapshot, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertEquals(2500, restored.total());
        assertEquals(2500, new RenderInventory(restored.save()).total());
    }
    @Test void regionHeaderInventoryHonorsRadiusAndDeduplicatesLoadedChunks() throws Exception {
        var bytes = ByteBuffer.allocate(3 * 4096);
        bytes.putInt(0, (2 << 8) | 1); bytes.putInt(4, (2 << 8) | 1);
        Files.write(root.resolve("r.0.0.mca"), bytes.array());
        var snapshot = new MissingTileCheck.Snapshot(root, List.of(new DynmapChunk(0, 0), new DynmapChunk(40, 0)), null);
        var inventory = new RenderInventory();
        assertTrue(inventory.step(mock(DynmapWorld.class), map(), snapshot, 0, 0, 0, 0));
        assertEquals(1, inventory.total());
    }
    @Test void rejectsInvalidPhaseAndInterruptionDoesNotMarkWorkComplete() throws Exception {
        var invalid = new ConfigurationNode(); invalid.put("phase", "bogus");
        assertThrows(java.io.IOException.class, () -> new RenderInventory(invalid));
        var inventory = new RenderInventory();
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class, () -> inventory.step(mock(DynmapWorld.class), map(), new MissingTileCheck.Snapshot(null, List.of(), null), 0, 0, 0, 0));
        } finally { Thread.interrupted(); }
        assertFalse(inventory.done());
    }

    @Test void generatedRegionDirectoryCursorResumesAtTheNextFile() throws Exception {
        var bytes = ByteBuffer.allocate(3 * 4096); bytes.putInt(0, (2 << 8) | 1);
        for (int i = 0; i < 33; i++) Files.write(root.resolve("r." + i + ".0.mca"), bytes.array());
        var snapshot = new MissingTileCheck.Snapshot(root, List.of(), null);
        var world = mock(DynmapWorld.class); var map = map();
        var inventory = new RenderInventory();
        assertFalse(inventory.step(world, map, snapshot, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertEquals("regions", inventory.phase());
        var restored = new RenderInventory(inventory.save());
        assertTrue(restored.step(world, map, snapshot, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertEquals(33, restored.total());
    }
    @Test void disconnectedTerrainSeedsRemainBoundedAndSurviveRestart() throws Exception {
        var snapshot = new MissingTileCheck.Snapshot(null, List.of(new DynmapChunk(0, 0), new DynmapChunk(200, 200)), null);
        var inventory = new RenderInventory(); inventory.retainSeeds();
        assertTrue(inventory.step(mock(DynmapWorld.class), map(), snapshot, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE));
        var found = new TileFlags(); found.setFlag(0, 0, true);
        inventory = new RenderInventory(inventory.save());
        var seed = inventory.nextUnseenSeed(found); assertNotNull(seed); assertEquals(200, seed.x); assertEquals(200, seed.y);
        inventory = new RenderInventory(inventory.save());
        assertNull(inventory.nextUnseenSeed(found)); assertFalse(inventory.hasSeeds());
        assertEquals(2, inventory.total());
    }

}
