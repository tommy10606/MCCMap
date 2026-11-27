package org.dynmap.storage.filetree;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.dynmap.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class LegacyCleanupTest {
    @TempDir Path root;
    FileTreeMapStorage storage;DynmapWorld world;MapType map;
    private static final MapType.ImageVariant V=MapType.ImageVariant.STANDARD;
    @BeforeEach void setup()throws Exception {
        var core=new DynmapCore();core.configuration=new ConfigurationNode();core.setDataFolder(root.toFile());
        var field=DynmapCore.class.getDeclaredField("tilesDirectory");field.setAccessible(true);field.set(core,Files.createDirectories(root.resolve("tiles")).toFile());
        world=mock(DynmapWorld.class,withSettings().useConstructor("world",256,64).defaultAnswer(CALLS_REAL_METHODS));
        map=mock(MapType.class);when(map.getPrefix()).thenReturn("flat_new");when(map.getImageFormat()).thenReturn(MapType.ImageFormat.FORMAT_PNG);when(map.getVariants()).thenReturn(new MapType.ImageVariant[]{V});
        when(map.getTilePreviewSources()).thenReturn(List.of(new MapType.TilePreviewSource("flat_old",0,MapType.ImageEncoding.PNG)));
        world.maps.add(map);storage=new FileTreeMapStorage();assertTrue(storage.init(core));
    }
    private Path image(String prefix,int x,int y,int zoom,int argb)throws Exception {
        var p=root.resolve("tiles/world/"+prefix+"/"+(x>>5)+"_"+(y>>5)+"/"+(zoom==0?"":"z".repeat(zoom)+"_")+x+"_"+y+".png");Files.createDirectories(p.getParent());
        var image=new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB);for(int py=0;py<128;py++)for(int px=0;px<128;px++)image.setRGB(px,py,argb);assertTrue(ImageIO.write(image,"png",p.toFile()));image.flush();return p;
    }
    private LegacyCleanupCursor finish(LegacyCleanupCursor cursor)throws Exception {
        for(int step=0;step<500;step++)if(storage.cleanupLegacyStep(world,map,cursor,128))return cursor;
        fail("cleanup must finish its bounded test layout");return cursor;
    }
    @Test void removesCoveredBaseTilesButKeepsHolesCorruptionForeignFilesAndActiveLayouts()throws Exception {
        var removed=image("flat_old",-2,-3,0,0xff00ff00);image("flat_new",-2,-3,0,0xff0000ff);
        var hole=image("flat_old",2,3,0,0xff00ff00);
        var corrupt=image("flat_old",3,4,0,0xff00ff00);var bad=image("flat_new",3,4,0,0xff0000ff);Files.writeString(bad,"corrupt PNG");
        var unknown=image("flat_unknown",0,0,0,0xff00ff00);var current=image("flat_new",0,0,0,0xff0000ff);
        var cursor=finish(new LegacyCleanupCursor());assertFalse(Files.exists(removed));assertTrue(Files.exists(hole));assertTrue(Files.exists(corrupt));assertTrue(Files.exists(unknown));assertTrue(Files.exists(current));assertEquals(1,cursor.deleted);assertTrue(cursor.bytes>0);
    }
    @Test void overviewWaitsForEveryBaseAndOpaqueNativeOverview()throws Exception {
        var old=image("flat_old",0,0,1,0xff00ff00);
        for(int x=0;x<2;x++)for(int y=-1;y<=0;y++)image("flat_new",x,y,0,0xff0000ff);
        image("flat_new",0,0,1,0x00000000);finish(new LegacyCleanupCursor());assertTrue(Files.exists(old));
        image("flat_new",0,0,1,0xff0000ff);var missing=root.resolve("tiles/world/flat_new/0_-1/1_-1.png");Files.delete(missing);
        finish(new LegacyCleanupCursor());assertTrue(Files.exists(old));
        image("flat_new",1,-1,0,0xff0000ff);finish(new LegacyCleanupCursor());assertFalse(Files.exists(old));
    }
    @Test void downscaledNegativeSourcesAndRestartReplayKeepCoordinatesCorrect()throws Exception {
        when(map.getTilePreviewSources()).thenReturn(List.of(new MapType.TilePreviewSource("flat_old",-1,MapType.ImageEncoding.PNG)));
        var old=image("flat_old",-3,-3,0,0xff00ff00);image("flat_new",-2,-2,0,0xff0000ff);
        var cursor=new LegacyCleanupCursor();assertFalse(storage.cleanupLegacyStep(world,map,cursor,1));
        cursor=new LegacyCleanupCursor(cursor.save());finish(cursor);assertFalse(Files.exists(old));assertEquals(1,cursor.deleted);
    }
    @Test void upscaledSourceNeedsIntermediateZoomCoverageToo()throws Exception {
        when(map.getTilePreviewSources()).thenReturn(List.of(new MapType.TilePreviewSource("flat_old",1,MapType.ImageEncoding.PNG)));
        var old=image("flat_old",0,0,0,0xff00ff00);
        for(int x=0;x<2;x++)for(int y=0;y<2;y++)image("flat_new",x,y,0,0xff0000ff);
        finish(new LegacyCleanupCursor());assertTrue(Files.exists(old));
        image("flat_new",0,0,1,0xff0000ff);image("flat_new",0,2,1,0xff0000ff);
        finish(new LegacyCleanupCursor());assertFalse(Files.exists(old));
    }
    @Test void activeAliasAndSymlinkedSourcesAreNeverRemoved()throws Exception {
        var old=image("flat_old",0,0,0,0xff00ff00);image("flat_new",0,0,0,0xff0000ff);
        var active=mock(MapType.class);when(active.getPrefix()).thenReturn("flat_old");when(active.getVariants()).thenReturn(new MapType.ImageVariant[]{V});world.maps.add(active);
        finish(new LegacyCleanupCursor());assertTrue(Files.exists(old));world.maps.remove(active);
        var outside=root.resolve("outside");Files.createDirectories(outside);var outsideFile=outside.resolve("0_0.png");Files.move(old,outsideFile);Files.delete(old.getParent());Files.createSymbolicLink(old.getParent(),outside);
        finish(new LegacyCleanupCursor());assertTrue(Files.exists(outsideFile));
    }
    @Test void hugeOrOverflowingCoverageIsRetainedInsteadOfCreatingUnboundedWork()throws Exception {
        assertNull(LegacyCleanupPlan.create(0,0,20,4));assertNull(LegacyCleanupPlan.create(Integer.MAX_VALUE,0,1,4));
        var old=image("flat_old",0,0,20,0xff00ff00);finish(new LegacyCleanupCursor());assertTrue(Files.exists(old));
    }
    @Test void replacementMutationDuringAProofRetainsTheOldOverview()throws Exception {
        var old=image("flat_old",0,0,1,0xff00ff00);
        for(int x=0;x<2;x++)for(int y=-1;y<=0;y++)image("flat_new",x,y,0,0xff0000ff);
        image("flat_new",0,0,1,0xff0000ff);var cursor=new LegacyCleanupCursor();
        assertFalse(storage.cleanupLegacyStep(world,map,cursor,2));
        storage.purgeMapTiles(world,map);
        for(int x=0;x<2;x++)for(int y=-1;y<=0;y++)image("flat_new",x,y,0,0xff0000ff);
        image("flat_new",0,0,1,0xff0000ff);
        finish(cursor);assertTrue(Files.exists(old));assertEquals(0,cursor.deleted);
        finish(new LegacyCleanupCursor());assertFalse(Files.exists(old));
    }

}
