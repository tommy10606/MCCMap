package org.dynmap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.dynmap.storage.*;
import org.dynmap.storage.filetree.FileTreeMapStorage;
import org.dynmap.utils.ImageIOManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
class LegacyTileStorageTest {
    @TempDir Path root;
    MapType map;DynmapWorld world;FileTreeMapStorage storage;MapManager previous;
    @BeforeEach void setup() throws Exception {
        previous=MapManager.mapman;MapManager.mapman=mock(MapManager.class);
        var core=new DynmapCore();core.configuration=new ConfigurationNode();core.setDataFolder(root.toFile());
        var field=DynmapCore.class.getDeclaredField("tilesDirectory");field.setAccessible(true);field.set(core,Files.createDirectories(root.resolve("tiles")).toFile());
        map=mock(MapType.class);when(map.getPrefix()).thenReturn("flat_rnew");when(map.getImageFormat()).thenReturn(MapType.ImageFormat.FORMAT_PNG);when(map.getLegacyTilePrefix()).thenReturn("flat");when(map.getLegacyTileScaleShift()).thenReturn(2);when(map.getLegacyTileScaleShift(any(MapType.ImageEncoding.class))).thenAnswer(call -> map.getLegacyTileScaleShift());
        doCallRealMethod().when(map).getTilePreviewSources();
        world=mock(DynmapWorld.class,withSettings().useConstructor("world",256,64).defaultAnswer(CALLS_REAL_METHODS));storage=new FileTreeMapStorage();assertTrue(storage.init(core));
    }
    @AfterEach void teardown(){MapManager.mapman=previous;}
    private Path old(String prefix,int x,int y,int color) throws Exception {
        var file=root.resolve("tiles/world/"+prefix+"/"+(x>>5)+"_"+(y>>5)+"/"+x+"_"+y+".jpg");Files.createDirectories(file.getParent());
        var image=new BufferedImage(128,128,BufferedImage.TYPE_INT_RGB);for(int py=0;py<128;py++)for(int px=0;px<128;px++)image.setRGB(px,py,color);ImageIO.write(image,"jpg",file.toFile());return file;
    }
    @Test void previewDoesNotCountAsNativeAndARealRenderReplacesItWithoutChangingTheOldFile() throws Exception {
        var file=old("flat",-184,-137,0x00ff00);byte[] before=Files.readAllBytes(file);
        var tile=storage.getTile(world,map,-736,-545,0,MapType.ImageVariant.STANDARD);
        assertFalse(tile.exists());assertNull(tile.read());var preview=tile.readForDisplay();assertNotNull(preview);assertNotEquals(-1,preview.hashCode);assertEquals(MapType.ImageEncoding.PNG,preview.format);
        var decoded=ImageIOManager.imageIODecode(preview);assertTrue(((decoded.getRGB(64,64)>>8)&255)>240);assertFalse(tile.exists());
        var current=new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB);current.setRGB(64,64,0xff0000ff);assertTrue(tile.write(123,current,100));
        assertTrue(tile.exists());assertEquals(0xff0000ff,ImageIOManager.imageIODecode(tile.readForDisplay()).getRGB(64,64));assertArrayEquals(before,Files.readAllBytes(file));
    }
    private void render(int x,int y,int zoom) {
        var image=new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB);
        assertTrue(storage.getTile(world,map,x,y,zoom,MapType.ImageVariant.STANDARD).write(42,image,100));
    }
    @Test void upscaledOldBaseTilesWaitForReplacementOverviewsIncludingNegativeCoordinates() throws Exception {
        for(int oldX:new int[]{0,-184}) {
            int oldY=oldX==0?0:-137;var file=old("flat",oldX,oldY,0x00ff00);
            for(int dx=0;dx<4;dx++)for(int dy=0;dy<4;dy++) {
                assertTrue(Files.exists(file));render(oldX*4+dx,oldY*4+dy,0);
            }
            assertTrue(Files.exists(file),"upscaled sources must wait for replacement overviews too");
        }
    }
    @Test void oldZoomTileSurvivesPartiallyGeneratedNewOverviews() throws Exception {
        var original=old("flat",0,0,0x00ff00);var zoom=original.resolveSibling("z_0_0.jpg");Files.move(original,zoom);
        render(0,0,3);assertTrue(Files.exists(zoom));
        render(0,8,3);assertTrue(Files.exists(zoom));
    }
    @Test void failedReplacementAndDeletionNeverRetireOldCoverage() throws Exception {
        when(map.getLegacyTileScaleShift()).thenReturn(0);var file=old("flat",0,0,0x00ff00);
        var target=root.resolve("tiles/world/flat_rnew/0_0");Files.createDirectories(target.getParent());Files.writeString(target,"not a directory");
        var tile=storage.getTile(world,map,0,0,0,MapType.ImageVariant.STANDARD);
        assertFalse(tile.write(42,new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB),100));assertTrue(Files.exists(file));
        Files.delete(target);assertTrue(tile.write(0,(org.dynmap.utils.BufferOutputStream)null,0));assertTrue(Files.exists(file));
    }
    @Test void retiredSourcesNeverFollowLinkedDirectories() throws Exception {
        when(map.getLegacyTileScaleShift()).thenReturn(0);var file=old("outside",0,0,0x00ff00);
        Files.createSymbolicLink(root.resolve("tiles/world/flat"),file.getParent().getParent());
        render(0,0,0);assertTrue(Files.exists(file));
    }
    @Test void tileMetadataScanResumesInAnotherCursorWithoutLosingCoordinates() throws Exception {
        when(map.getVariants()).thenReturn(new MapType.ImageVariant[]{MapType.ImageVariant.STANDARD});
        render(-34,-34,0);render(0,0,0);render(34,34,0);
        var seen=new java.util.ArrayList<String>();var cursor=new org.dynmap.storage.filetree.TileScanCursor();boolean done=false;
        for(int i=0;i<20&&!done;i++){done=storage.scanTiles(world,map,cursor,1,tile->seen.add(tile.x+":"+tile.y));cursor=new org.dynmap.storage.filetree.TileScanCursor(cursor.save());}
        assertTrue(done);assertEquals(3,seen.size());assertEquals(3,new java.util.HashSet<>(seen).size());
    }
    @Test void unrelatedSameAddressJpegIsNeverServedAsTheCurrentPng() throws Exception {
        old("flat_rnew",0,0,0xff0000);when(map.getLegacyTilePrefix()).thenReturn(null);
        var tile=storage.getTile(world,map,0,0,0,MapType.ImageVariant.STANDARD);assertFalse(tile.exists());assertNull(tile.readForDisplay());
        when(map.getLegacyTilePrefix()).thenReturn("flat_rnew");assertNull(tile.readForDisplay());
    }
    @Test void legacyPreviewsCannotReadThroughLinkedDirectories() throws Exception {
        var target=Files.createDirectory(root.resolve("outside"));Files.createDirectories(root.resolve("tiles/world"));Files.createSymbolicLink(root.resolve("tiles/world/flat"),target);
        var tile=storage.getTile(world,map,0,0,0,MapType.ImageVariant.STANDARD);assertNull(tile.readForDisplay());
    }
    private Path oldPng(int x, int y, int zoom, int color) throws Exception {
        String prefix = zoom == 0 ? "" : "z".repeat(zoom) + "_";
        var file = root.resolve("tiles/world/flat/"+(x>>5)+"_"+(y>>5)+"/"+prefix+x+"_"+y+".png");
        Files.createDirectories(file.getParent());
        var image = new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB);
        for(int py=0;py<128;py++)for(int px=0;px<128;px++)image.setRGB(px,py,color);
        ImageIO.write(image,"png",file.toFile()); return file;
    }
    @Test void existingSameGridPngIsServedWithoutMovingOrRenamingIt() throws Exception {
        when(map.getLegacyTileScaleShift()).thenReturn(0);
        var old = oldPng(-1125,-623,0,0xff00ff00); byte[] bytes = Files.readAllBytes(old);
        var tile = storage.getTile(world,map,-1125,-623,0,MapType.ImageVariant.STANDARD);
        assertFalse(tile.exists()); assertEquals(0xff00ff00,ImageIOManager.imageIODecode(tile.readForDisplay()).getRGB(64,64));
        assertArrayEquals(bytes,Files.readAllBytes(old));
        var replacement = new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB); replacement.setRGB(64,64,0xff0000ff);
        assertTrue(tile.write(52,replacement,101)); assertFalse(Files.exists(old)); assertTrue(tile.exists());
    }
    @Test void partialNativeOverviewKeepsOldPngCoverageAndDoesNotAlterStoredPixels() throws Exception {
        when(map.getLegacyTileScaleShift()).thenReturn(0);
        var old = oldPng(0,0,2,0xff00ff00); byte[] oldBytes = Files.readAllBytes(old);
        var tile = storage.getTile(world,map,0,0,2,MapType.ImageVariant.STANDARD);
        var patch = new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB);patch.setRGB(64,64,0xff0000ff);
        assertTrue(tile.write(53,patch,102));
        var image = ImageIOManager.imageIODecode(tile.readForDisplay());
        assertEquals(0xff0000ff,image.getRGB(64,64)); assertEquals(0xff00ff00,image.getRGB(10,10));
        assertArrayEquals(oldBytes,Files.readAllBytes(old));
        var nativeImage = ImageIO.read(root.resolve("tiles/world/flat_rnew/0_0/zz_0_0.png").toFile());
        assertEquals(0,nativeImage.getRGB(10,10));
    }
    @Test void opaqueNativeOverviewReadRemainsDecodableWithoutLegacyFiles() throws Exception {
        Files.createDirectories(root.resolve("tiles/world/flat"));
        var tile = storage.getTile(world,map,0,0,1,MapType.ImageVariant.STANDARD);
        var image = new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<128;y++)for(int x=0;x<128;x++)image.setRGB(x,y,0xffff0000);
        assertTrue(tile.write(54,image,103)); assertEquals(0xffff0000,ImageIOManager.imageIODecode(tile.readForDisplay()).getRGB(10,10));
    }

    @Test void mixedGenerationsKeepPngPixelsAtTheirOwnCoordinatesAndRetireEachGridSeparately() throws Exception {
        doReturn(0).when(map).getLegacyTileScaleShift(MapType.ImageEncoding.PNG);
        int x=-1125,y=-623;
        var oldPng=oldPng(x,y,0,0xff000000);
        var pattern=new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB);
        for(int py=0;py<128;py++)for(int px=0;px<128;px++)pattern.setRGB(px,py,0xff000000|(px<<16)|(py<<8)|(px^py));
        ImageIO.write(pattern,"png",oldPng.toFile());
        // The low-resolution JPEG at the same world location uses a different address.
        var oldJpeg=old("flat",Math.floorDiv(x,4),Math.floorDiv(y+1,4),0x00ff00);
        // A same-address PNG on that JPEG grid must not be stretched into this tile.
        var decoy=oldPng(Math.floorDiv(x,4),Math.floorDiv(y+1,4),0,0xffff0000);
        var tile=storage.getTile(world,map,x,y,0,MapType.ImageVariant.STANDARD);
        assertNull(tile.read());
        var decoded=ImageIOManager.imageIODecode(tile.readForDisplay());
        for(int py=0;py<128;py++)for(int px=0;px<128;px++)assertEquals(pattern.getRGB(px,py),decoded.getRGB(px,py));
        assertTrue(tile.write(55,pattern,101));
        assertFalse(Files.exists(oldPng));assertTrue(Files.exists(oldJpeg));assertTrue(Files.exists(decoy));
        int left=Math.floorDiv(x,4)*4,top=Math.floorDiv(y+1,4)*4+4;
        // Upscaled JPEG coverage also supplies intermediate zoom levels until those are ready.
        for(int dx=0;dx<4;dx++)for(int dy=0;dy<4;dy++)render(left+dx,top-1-dy,0);
        assertTrue(Files.exists(oldJpeg));assertTrue(Files.exists(decoy));
    }

    @Test void recoveredTileContentChangesInvalidateBrowserCacheEvenIfTheTimestampIsUnchanged() throws Exception {
        doReturn(0).when(map).getLegacyTileScaleShift(MapType.ImageEncoding.PNG);
        var file=oldPng(0,0,0,0xffff0000);var timestamp=Files.getLastModifiedTime(file);
        var tile=storage.getTile(world,map,0,0,0,MapType.ImageVariant.STANDARD);
        var before=tile.readForDisplay();oldPng(0,0,0,0xff0000ff);Files.setLastModifiedTime(file,timestamp);
        var after=tile.readForDisplay();assertEquals(before.lastModified,after.lastModified);assertNotEquals(before.hashCode,after.hashCode);
    }

    @Test void lowerResolutionPreviewCombinesTheCorrectFourHighResolutionBasesAndRetiresOnlyTheirFootprint() throws Exception {
        doReturn(java.util.List.of(new MapType.TilePreviewSource("flat",-1,MapType.ImageEncoding.PNG))).when(map).getTilePreviewSources();
        int x=-563,y=-312;int[][] colors={{0xFFFF0000,0xFF00FF00},{0xFF0000FF,0xFFFFFF00}};
        var files=new java.util.ArrayList<Path>();
        for(int dx=0;dx<2;dx++)for(int dy=0;dy<2;dy++)files.add(oldPng(2*x+dx,2*y+1-dy,0,colors[dy][dx]));
        var untouched=oldPng(2*x+2,2*y,0,0xFF00FFFF);
        var tile=storage.getTile(world,map,x,y,0,MapType.ImageVariant.STANDARD);assertNull(tile.read());
        var image=ImageIOManager.imageIODecode(tile.readForDisplay());
        assertEquals(colors[0][0],image.getRGB(20,20));assertEquals(colors[0][1],image.getRGB(100,20));
        assertEquals(colors[1][0],image.getRGB(20,100));assertEquals(colors[1][1],image.getRGB(100,100));
        assertTrue(tile.write(88,image,200));
        for(var file:files)assertFalse(Files.exists(file));assertTrue(Files.exists(untouched));
    }
    @Test void failedLowerResolutionWritePreservesEveryHighResolutionSource() throws Exception {
        doReturn(java.util.List.of(new MapType.TilePreviewSource("flat",-1,MapType.ImageEncoding.PNG))).when(map).getTilePreviewSources();
        var source=oldPng(0,0,0,0xFF00FF00);var parent=root.resolve("tiles/world/flat_rnew/0_0");Files.createDirectories(parent.getParent());Files.writeString(parent,"blocked");
        var tile=storage.getTile(world,map,0,0,0,MapType.ImageVariant.STANDARD);
        assertFalse(tile.write(9,new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB),200));assertTrue(Files.exists(source));
    }

    @Test void httpRecoveryAtLowerResolutionKeepsTheWorldFeatureUnderItsMarkerAndNeverCreatesNativePixels() throws Exception {
        doReturn(java.util.List.of(new MapType.TilePreviewSource("flat",-1,MapType.ImageEncoding.PNG))).when(map).getTilePreviewSources();
        var old=oldPng(-1125,-623,0,0);var feature=new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB);
        for(int y=70;y<74;y++)for(int x=6;x<10;x++)feature.setRGB(x,y,0xFFFF0000);
        ImageIO.write(feature,"png",old.toFile());world.maps.add(map);
        when(map.getVariants()).thenReturn(new MapType.ImageVariant[]{MapType.ImageVariant.STANDARD});
        doReturn(storage).when(world).getMapStorage();when(MapManager.mapman.getWorld("world")).thenReturn(world);
        var core=mock(DynmapCore.class);core.mapManager=MapManager.mapman;
        var handler=new org.dynmap.servlet.MapStorageResourceHandler();handler.setCore(core);
        var request=mock(javax.servlet.http.HttpServletRequest.class);when(request.getPathInfo()).thenReturn("/world/flat_rnew/-18_-10/-563_-312.png");
        var response=mock(javax.servlet.http.HttpServletResponse.class);var bytes=new java.io.ByteArrayOutputStream();
        var output=new javax.servlet.ServletOutputStream(){public boolean isReady(){return true;}public void setWriteListener(javax.servlet.WriteListener listener){}public void write(int b){bytes.write(b);}};
        when(response.getOutputStream()).thenReturn(output);
        handler.handle("/world/flat_rnew/-18_-10/-563_-312.png",mock(org.eclipse.jetty.ee8.nested.Request.class),request,response);
        var image=ImageIO.read(new java.io.ByteArrayInputStream(bytes.toByteArray()));assertNotNull(image);
        assertEquals(0xFFFF0000,image.getRGB(68,36));assertEquals(0,image.getRGB(8,72));
        verify(response).setContentType("image/png");verify(response).setHeader(eq("ETag"),argThat(value->!value.equals("\"-1\"")));
        var tile=storage.getTile(world,map,-563,-312,0,MapType.ImageVariant.STANDARD);assertFalse(tile.exists());assertNull(tile.read());assertTrue(Files.exists(old));
    }

    @Test void delayedFullRenderCleanupPreservesPlayerUpdatesWrittenAfterTheInventoryStarted() throws Exception {
        render(0,0,0);render(1,0,0);
        var old=root.resolve("tiles/world/flat_rnew/0_0/0_0.png");var fresh=root.resolve("tiles/world/flat_rnew/0_0/1_0.png");
        Files.setLastModifiedTime(old,java.nio.file.attribute.FileTime.fromMillis(1000));Files.setLastModifiedTime(fresh,java.nio.file.attribute.FileTime.fromMillis(3000));
        var hd=mock(org.dynmap.hdmap.HDMap.class);when(hd.getName()).thenReturn("flat");when(hd.getPrefix()).thenReturn("flat_rnew");when(hd.getImageFormat()).thenReturn(MapType.ImageFormat.FORMAT_PNG);when(hd.getVariants()).thenReturn(new MapType.ImageVariant[]{MapType.ImageVariant.STANDARD});
        doReturn(storage).when(world).getMapStorage();doCallRealMethod().when(hd).purgeOldTiles(eq(world),any(org.dynmap.utils.TileFlags.class),eq(2000L));
        hd.purgeOldTiles(world,new org.dynmap.utils.TileFlags(),2000);
        assertFalse(Files.exists(old));assertTrue(Files.exists(fresh));
    }

    @Test void inlineRetirementAlsoProtectsAnotherActiveMapLayout() throws Exception {
        when(map.getLegacyTileScaleShift()).thenReturn(0);var file=old("flat",0,0,0x00ff00);
        var other=mock(MapType.class);when(other.getPrefix()).thenReturn("flat");when(other.getVariants()).thenReturn(new MapType.ImageVariant[]{MapType.ImageVariant.STANDARD});world.maps.add(other);
        render(0,0,0);assertTrue(Files.exists(file));
    }

}
