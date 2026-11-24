package org.dynmap.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.dynmap.*;
import org.dynmap.storage.filetree.FileTreeMapStorage;
import org.dynmap.utils.BufferOutputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class FileStorageSecurityTest {
    @TempDir Path root;
    Path tiles;
    FileTreeMapStorage storage;
    DynmapWorld world;
    MapType map;
    @BeforeEach void setup() throws Exception {
        tiles=Files.createDirectories(root.resolve("tiles"));
        DynmapCore core=new DynmapCore(); core.configuration=new ConfigurationNode(); core.setDataFolder(root.toFile());
        var field=DynmapCore.class.getDeclaredField("tilesDirectory"); field.setAccessible(true); field.set(core,tiles.toFile());
        storage=new FileTreeMapStorage(); assertTrue(storage.init(core));
        world=mock(DynmapWorld.class); when(world.getName()).thenReturn("world");
        map=mock(MapType.class); when(map.getPrefix()).thenReturn("flat"); when(map.getName()).thenReturn("flat");
        when(map.getImageFormat()).thenReturn(MapType.ImageFormat.FORMAT_PNG);
        when(map.getVariants()).thenReturn(new MapType.ImageVariant[]{MapType.ImageVariant.STANDARD});
        world.maps=new ArrayList<>(List.of(map));
    }
    @Test void malformedPublicTileCoordinatesAreRejectedWithoutExceptions() {
        for(String uri:List.of("flat/0_0/_0.png","flat/0_0/"+"z".repeat(33)+"_0_0.png","flat/0_0/xyz_0.png","flat/0_0/0_0.png/extra"))
            assertNull(assertDoesNotThrow(()->storage.getTile(world,uri)),uri);
    }
    @Test void markerReadsAndWritesCannotEscapeMarkerDirectory() throws Exception {
        Files.createDirectories(tiles.resolve("_markers_")); Files.writeString(root.resolve("secret.png"),"private");
        assertNull(storage.getMarkerImage("../../secret"));
        var bytes=new BufferOutputStream(); bytes.write(new byte[]{1});
        assertFalse(storage.setMarkerImage("../../created",bytes)); assertFalse(Files.exists(root.resolve("created.png")));
        assertTrue(storage.setMarkerImage("group/icon",bytes)); assertNotNull(storage.getMarkerImage("group/icon"));
    }
    @Test void publicImageReadsRejectLinkedFilesAndDirectories() throws Exception {
        Path markers=Files.createDirectories(tiles.resolve("_markers_")); Path outside=Files.createDirectories(root.resolve("private"));
        Files.writeString(outside.resolve("secret.png"),"private");
        Files.createSymbolicLink(markers.resolve("leak.png"),outside.resolve("secret.png"));
        Files.createSymbolicLink(markers.resolve("linked"),outside);
        assertNull(storage.getMarkerImage("leak")); assertNull(storage.getMarkerImage("linked/secret"));
        var bytes=new BufferOutputStream();bytes.write(new byte[]{2});
        assertFalse(storage.setMarkerImage("linked/new",bytes));assertFalse(Files.exists(outside.resolve("new.png")));
    }
    @Test void oversizedLocalImageIsRejectedBeforeAllocation() throws Exception {
        Path image=Files.createDirectories(tiles.resolve("_markers_")).resolve("large.png");
        try(var file=new java.io.RandomAccessFile(image.toFile(),"rw")){file.setLength(33L*1024*1024);}
        assertNull(storage.getMarkerImage("large"));
    }
    @Test void purgeDoesNotFollowExternalDirectoriesOrCycles() throws Exception {
        Path folder=Files.createDirectories(tiles.resolve("world/flat/0_0"));Files.write(folder.resolve("0_0.png"),new byte[]{1});
        Path outside=Files.createDirectories(root.resolve("private"));Files.writeString(outside.resolve("keep.txt"),"private");
        Files.createSymbolicLink(folder.getParent().resolve("outside"),outside);
        Files.createSymbolicLink(folder.getParent().resolve("cycle"),folder.getParent());
        assertTimeoutPreemptively(Duration.ofSeconds(2),()->storage.purgeMapTiles(world,map));
        assertEquals("private",Files.readString(outside.resolve("keep.txt")));
    }
}
