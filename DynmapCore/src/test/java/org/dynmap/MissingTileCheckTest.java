package org.dynmap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.*;
import java.nio.file.*;
import org.dynmap.utils.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class MissingTileCheckTest {
    @TempDir Path root;
    @Test void regionHeadersSelectOnlyExistingChunksInsideTheBorderAndIgnoreLinkedFiles() throws Exception {
        var bytes=ByteBuffer.allocate(3*4096);bytes.putInt(0,(2<<8)|1);bytes.putInt((3+3*32)*4,(2<<8)|1);
        Files.write(root.resolve("r.0.0.mca"),bytes.array());Files.write(root.resolve("r.1.0.mca"),bytes.array());
        var border=new Polygon();border.addVertex(0,0);border.addVertex(32,0);border.addVertex(32,32);border.addVertex(0,32);
        var chunks=new TileFlags();MissingTileCheck.readRegionHeaders(root,border,chunks);
        assertEquals(1,chunks.countFlags());assertTrue(chunks.getFlag(0,0));assertFalse(MissingTileCheck.inside(border,2,0));
        Path other=Files.createDirectory(root.resolve("other"));Files.createSymbolicLink(other.resolve("r.0.0.mca"),root.resolve("r.0.0.mca"));
        var empty=new TileFlags();MissingTileCheck.readRegionHeaders(other,null,empty);assertEquals(0,empty.countFlags());
    }
    @Test void missingZoomParentIsQueuedThroughItsExistingChildOnlyOnce() {
        var base=mock(org.dynmap.storage.MapStorageTile.class);
        var first=mock(org.dynmap.storage.MapStorageTile.class);
        var second=mock(org.dynmap.storage.MapStorageTile.class);
        when(base.getZoomOutTile()).thenReturn(first);when(first.exists()).thenReturn(true);
        when(first.getZoomOutTile()).thenReturn(second);when(second.exists()).thenReturn(false);
        assertEquals(1,MissingTileCheck.repairZoomChain(base,4));
        verify(first).enqueueZoomOutUpdate();verify(base,never()).enqueueZoomOutUpdate();
        when(second.exists()).thenReturn(true);
        assertEquals(0,MissingTileCheck.repairZoomChain(base,2));
        verify(first,times(1)).enqueueZoomOutUpdate();
        assertEquals(0,MissingTileCheck.repairZoomChain(base,0));
    }
}
