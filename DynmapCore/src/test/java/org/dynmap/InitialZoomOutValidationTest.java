package org.dynmap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import org.dynmap.storage.*;
import org.dynmap.storage.filetree.FileTreeMapStorage;
import org.dynmap.utils.ImageIOManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class InitialZoomOutValidationTest {
    @TempDir Path root;
    private DynmapWorld world;
    private MapType map;
    private FileTreeMapStorage storage;
    private MapManager previous;
    @BeforeEach void setup() throws Exception {
        previous = MapManager.mapman; MapManager.mapman = mock(MapManager.class);
        DynmapWorld.doInitialScan(true);
        DynmapCore core = new DynmapCore(); core.configuration = new ConfigurationNode(); core.setDataFolder(root.toFile());
        var tiles = DynmapCore.class.getDeclaredField("tilesDirectory"); tiles.setAccessible(true); tiles.set(core, Files.createDirectories(root.resolve("tiles")).toFile());
        world = mock(DynmapWorld.class, withSettings().useConstructor("world", 256, 64).defaultAnswer(CALLS_REAL_METHODS));
        map = mock(MapType.class); when(map.getPrefix()).thenReturn("flat"); when(map.getName()).thenReturn("flat");
        when(map.getMapZoomOutLevels()).thenReturn(3); when(map.getTileSize()).thenReturn(8);
        when(map.getVariants()).thenReturn(new MapType.ImageVariant[]{MapType.ImageVariant.STANDARD});
        when(map.getImageFormat()).thenReturn(MapType.ImageFormat.FORMAT_PNG);
        doCallRealMethod().when(map).getTilePreviewSources();
        world.maps.add(map); resetQueues(); storage = new FileTreeMapStorage(); assertTrue(storage.init(core));
        var field = DynmapWorld.class.getDeclaredField("storage"); field.setAccessible(true); field.set(world, storage);
    }
    @AfterEach void teardown() { MapManager.mapman = previous; DynmapWorld.doInitialScan(true); }
    private void resetQueues() { world.mapstate.clear(); world.mapstate.add(new MapTypeState(world, map)); }
    private MapStorageTile tile(int x, int y, int zoom) { return storage.getTile(world, map, x, y, zoom, MapType.ImageVariant.STANDARD); }
    private void write(MapStorageTile tile, int color, long date) {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y=0;y<8;y++) for(int x=0;x<8;x++) image.setRGB(x,y,color);
        assertTrue(tile.write(1, image, date));
    }
    @Test void lostQueuesRebuildMissingZoomLevelsFromExistingBaseTiles() throws Exception {
        write(tile(0,0,0), 0xFFFF0000, 2000); resetQueues();
        assertFalse(tile(0,0,1).exists());
        world.freshenZoomOutFiles();
        for (int level=1;level<=3;level++) {
            var parent=tile(0,0,level); assertTrue(parent.exists(), "level " + level);
            BufferedImage result=ImageIOManager.imageIODecode(parent.read());
            assertTrue(java.util.stream.IntStream.range(0,64).anyMatch(i->(result.getRGB(i%8,i/8)&0x00FFFFFF)==0x00FF0000));
        }
    }
    @Test void staleIntermediateTileIsQueuedButCurrentParentsAndTopLevelAreSkipped() {
        write(tile(0,0,0),0xFFFF0000,2000); write(tile(0,0,1),0xFF0000FF,1000);
        write(tile(0,0,2),0xFF00FF00,3000); write(tile(0,0,3),0xFF00FF00,4000); resetQueues();
        assertTrue(world.validateInitialZoomOut());
        MapTypeState state=world.mapstate.get(0); state.startZoomOutIter(); var c=new MapTypeState.ZoomOutCoord();
        assertTrue(state.nextZoomOutInv(c)); assertEquals(0,c.zoomlevel);
        state.clearZoomOutInv(c.x,c.y,c.zoomlevel);
        assertFalse(state.nextZoomOutInv(c));
        assertTrue(world.validateInitialZoomOut()); state.startZoomOutIter(); assertFalse(state.nextZoomOutInv(c));
    }
    @Test void disabledValidationLeavesExistingTilesAndLostQueuesAlone() {
        write(tile(-2,2,0),0xFFFF0000,2000); resetQueues(); DynmapWorld.doInitialScan(false);
        world.freshenZoomOutFiles(); assertFalse(tile(-2,2,0).getZoomOutTile().exists());
        MapTypeState state=world.mapstate.get(0); state.startZoomOutIter(); assertFalse(state.nextZoomOutInv(new MapTypeState.ZoomOutCoord()));
    }
    @Test void cancelledAndStoragePausedWorldsDeferValidationUntilTheyResume() {
        write(tile(-2,2,0),0xFFFF0000,2000); resetQueues(); var parent=tile(-2,2,0).getZoomOutTile();
        world.cancelZoomOutFreshen(); world.freshenZoomOutFiles(); assertFalse(parent.exists());
        world.activateZoomOutFreshen(); when(MapManager.mapman.storagePauseReason()).thenReturn("storage budget");
        world.freshenZoomOutFiles(); assertFalse(parent.exists());
        when(MapManager.mapman.storagePauseReason()).thenReturn(null); world.freshenZoomOutFiles(); assertTrue(parent.exists());
    }
    @Test void pendingZoomCoordinatesSurviveAnInterruptedPass() {
        MapTypeState state=world.mapstate.get(0);state.setZoomOutInv(0,0,0);state.startZoomOutIter();
        state.setZoomOutInv(4,4,0);state.startZoomOutIter();
        var coord=new MapTypeState.ZoomOutCoord();int count=0;
        while(state.nextZoomOutInv(coord)){state.clearZoomOutInv(coord.x,coord.y,coord.zoomlevel);count++;}
        assertEquals(2,count);
    }
    @Test void legacyDisplayUpgradeRebuildsNewerAndOrphanedOverviewsOnceWithoutTouchingBaseTiles() throws Exception {
        var core=new DynmapCore();core.configuration=new ConfigurationNode();core.setDataFolder(root.toFile());
        var web=DynmapCore.class.getDeclaredField("webpath");web.setAccessible(true);web.set(core,"web");
        when(MapManager.mapman.getCoreForCheckpoints()).thenReturn(core);
        when(MapManager.mapman.checkpointWorld(any(DynmapWorld.class))).thenReturn(true);
        when(map.getLegacyTilePrefix()).thenReturn("oldflat");
        write(tile(0,0,0),0xFFFF0000,1000);
        write(tile(0,0,1),0xFF0000FF,3000);
        write(tile(8,8,1),0xFF0000FF,3000); // Preview baked into a parent with no native children.
        byte[] base=Files.readAllBytes(root.resolve("tiles/world/flat/0_0/0_0.png"));resetQueues();
        world.freshenZoomOutFiles();
        assertArrayEquals(base,Files.readAllBytes(root.resolve("tiles/world/flat/0_0/0_0.png")));
        assertFalse(tile(8,8,1).exists());
        var rebuilt=ImageIOManager.imageIODecode(tile(0,0,1).read());
        assertEquals(0,java.util.stream.IntStream.range(0,64).filter(i->rebuilt.getRGB(i%8,i/8)==0xFF0000FF).count());
        assertTrue(java.util.stream.IntStream.range(0,64).anyMatch(i->rebuilt.getRGB(i%8,i/8)==0xFFFF0000));
        // A second startup performs normal timestamp validation, rather than forcing repair again.
        var second=mock(DynmapWorld.class,withSettings().useConstructor("world",256,64).defaultAnswer(CALLS_REAL_METHODS));
        second.maps.add(map);second.mapstate.add(new MapTypeState(second,map));
        var field=DynmapWorld.class.getDeclaredField("storage");field.setAccessible(true);field.set(second,storage);
        assertTrue(second.validateInitialZoomOut());var state=second.mapstate.getFirst();state.startZoomOutIter();
        assertFalse(state.nextZoomOutInv(new MapTypeState.ZoomOutCoord()));
    }

    @Test void zoomGenerationNeverStoresWebOnlyLegacyPixels() throws Exception {
        when(map.getTileSize()).thenReturn(128);when(map.getLegacyTilePrefix()).thenReturn("oldflat");resetQueues();
        var old=root.resolve("tiles/world/oldflat/0_0/0_0.png");Files.createDirectories(old.getParent());
        var image=new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<128;y++)for(int x=0;x<128;x++)image.setRGB(x,y,0xFF00FF00);
        javax.imageio.ImageIO.write(image,"png",old.toFile());
        var base=tile(0,0,0);assertNotNull(base.readForDisplay());assertNull(base.read());
        world.enqueueZoomOutUpdate(base);world.freshenZoomOutFiles();
        assertFalse(tile(0,0,1).exists());assertTrue(Files.exists(old));
    }

}
