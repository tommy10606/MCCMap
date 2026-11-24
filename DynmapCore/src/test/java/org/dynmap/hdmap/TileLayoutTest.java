package org.dynmap.hdmap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.dynmap.*;
import org.junit.jupiter.api.Test;
class TileLayoutTest {
    private HDPerspective perspective(String name,int scale,int angle){var c=new ConfigurationNode();c.put("name",name);c.put("scale",scale);c.put("inclination",angle);c.put("azimuth",180);return new IsoHDPerspective(null,c);}
    @Test void geometryChangesUseDifferentRasterNamespacesWhileTheSameGeometryIsStable(){var a=perspective("iso_S_90_lowres",4,90);var b=perspective("iso_S_90_hires",16,90);var c=perspective("iso_S_90_hires",16,30);var png=MapType.ImageFormat.FORMAT_PNG;String first=TileLayout.namespace("flat",a,0,0,png);assertEquals(first,TileLayout.namespace("flat",a,0,0,png));assertNotEquals(first,TileLayout.namespace("flat",b,0,0,png));assertNotEquals(TileLayout.namespace("flat",b,0,0,png),TileLayout.namespace("flat",c,0,0,png));assertNotEquals(first,TileLayout.namespace("flat",a,1,0,png));}
    @Test void onlyProvenUniformScaleChangesArePreviewCompatible(){var old=perspective("iso_S_90_lowres",4,90);var current=perspective("iso_S_90_hires",16,90);assertEquals(2,TileLayout.compatibleShift(old,current));assertEquals(0,TileLayout.compatibleShift(old,old));assertEquals(-1,TileLayout.compatibleShift(old,perspective("iso_S_90_hires",16,30)));assertEquals(-1,TileLayout.compatibleShift(old,perspective("custom",16,90)));}
    @Test void savingAndReloadingKeepsConfiguredNamesAndTheVerifiedPreviewMapping() {
        var previous=MapManager.mapman;
        try {
            var core=mock(DynmapCore.class);core.configuration=new ConfigurationNode();
            var manager=mock(MapManager.class);manager.hdmapman=new HDMapManager();MapManager.mapman=manager;
            core.mapManager=manager;when(core.getDefImageFormat()).thenReturn("jpg-q90");
            var low=perspective("iso_S_90_lowres",4,90);var high=perspective("iso_S_90_hires",16,90);
            manager.hdmapman.perspectives.put(low.getName(),low);manager.hdmapman.perspectives.put(high.getName(),high);
            var shader=mock(HDShader.class);when(shader.getName()).thenReturn("stdtexture");manager.hdmapman.shaders.put("stdtexture",shader);
            var lighting=mock(HDLighting.class);when(lighting.getName()).thenReturn("default");manager.hdmapman.lightings.put("default",lighting);
            var c=new ConfigurationNode();c.put("name","flat");c.put("prefix","flat");c.put("perspective",low.getName());c.put("shader","stdtexture");
            var first=new HDMap(core,c);String raster=first.getPrefix();assertNotEquals("flat",raster);assertEquals(2,first.getLegacyTileScaleShift());assertEquals(0,first.getLegacyTileScaleShift(MapType.ImageEncoding.PNG));
            first.setReadOnly(true);assertEquals(raster,first.getPrefix());
            var saved=first.saveConfiguration();assertEquals("flat",saved.getString("prefix",""));
            var reloaded=new HDMap(core,saved);assertEquals(raster,reloaded.getPrefix());assertEquals("flat",reloaded.getLegacyTilePrefix());assertEquals(2,reloaded.getLegacyTileScaleShift());assertEquals(0,reloaded.getLegacyTileScaleShift(MapType.ImageEncoding.PNG));
            var oldHint = first.saveConfiguration(); oldHint.remove("legacy-preview-png-scale-shift");
            assertEquals(0,new HDMap(core,oldHint).getLegacyTileScaleShift(MapType.ImageEncoding.PNG));
            reloaded.setPerspective(perspective("iso_S_30_hires",16,30));assertNotEquals(raster,reloaded.getPrefix());assertNull(reloaded.getLegacyTilePrefix());
        }finally{MapManager.mapman=previous;}
    }    @Test void pngMigrationAndSameGridRestartKeepTheVerifiedSourceMapping() {
        var previous=MapManager.mapman;
        try {
            var core=mock(DynmapCore.class);core.configuration=new ConfigurationNode();
            var manager=mock(MapManager.class);manager.hdmapman=new HDMapManager();MapManager.mapman=manager;
            core.mapManager=manager;when(core.getDefImageFormat()).thenReturn("png");
            var low=perspective("iso_S_90_lowres",4,90);var high=perspective("iso_S_90_hires",16,90);
            manager.hdmapman.perspectives.put(low.getName(),low);manager.hdmapman.perspectives.put(high.getName(),high);
            var shader=mock(HDShader.class);when(shader.getName()).thenReturn("stdtexture");manager.hdmapman.shaders.put("stdtexture",shader);
            var lighting=mock(HDLighting.class);when(lighting.getName()).thenReturn("default");manager.hdmapman.lightings.put("default",lighting);
            var c=new ConfigurationNode();c.put("name","flat");c.put("prefix","flat");c.put("perspective",low.getName());c.put("shader","stdtexture");
            var first=new HDMap(core,c);String raster=first.getPrefix();assertNotEquals("flat",raster);assertEquals(2,first.getLegacyTileScaleShift());assertEquals(0,first.getLegacyTileScaleShift(MapType.ImageEncoding.PNG));
            first.setReadOnly(true);assertEquals(raster,first.getPrefix());
            var saved=first.saveConfiguration();assertEquals("flat",saved.getString("prefix",""));
            var reloaded=new HDMap(core,saved);assertEquals(raster,reloaded.getPrefix());assertEquals("flat",reloaded.getLegacyTilePrefix());assertEquals(2,reloaded.getLegacyTileScaleShift());assertEquals(0,reloaded.getLegacyTileScaleShift(MapType.ImageEncoding.PNG));
            var oldHint = first.saveConfiguration(); oldHint.remove("legacy-preview-png-scale-shift");
            assertEquals(0,new HDMap(core,oldHint).getLegacyTileScaleShift(MapType.ImageEncoding.PNG));
            var explicitPng = new ConfigurationNode(); explicitPng.put("name","flat"); explicitPng.put("prefix","flat"); explicitPng.put("perspective",low.getName()); explicitPng.put("shader","stdtexture"); explicitPng.put("image-format","png");
            var explicit = new HDMap(core,explicitPng); assertEquals(2,explicit.getLegacyTileScaleShift(MapType.ImageEncoding.PNG));
            assertEquals(2,new HDMap(core,explicit.saveConfiguration()).getLegacyTileScaleShift(MapType.ImageEncoding.PNG));
            var sameGrid = new ConfigurationNode(); sameGrid.put("name","flat"); sameGrid.put("prefix","flat"); sameGrid.put("perspective",high.getName()); sameGrid.put("shader","stdtexture");
            var unchanged = new HDMap(core,sameGrid); assertEquals(0,unchanged.getLegacyTileScaleShift());
            sameGrid.put("legacy-preview-layout","different-layout");sameGrid.put("legacy-preview-scale-shift",2);
            assertNull(new HDMap(core,sameGrid).getLegacyTilePrefix());
            reloaded.setPerspective(perspective("iso_S_30_hires",16,30));assertNotEquals(raster,reloaded.getPrefix());assertNull(reloaded.getLegacyTilePrefix());
        }finally{MapManager.mapman=previous;}
    }
    @Test void balancedUpgradeRecoversTheActualVersionedHighGridAndPersistsItAcrossRestarts() {
        var previous=MapManager.mapman;
        try {
            var core=mock(DynmapCore.class);core.configuration=new ConfigurationNode();
            var manager=mock(MapManager.class);manager.hdmapman=new HDMapManager();MapManager.mapman=manager;core.mapManager=manager;
            when(core.getDefImageFormat()).thenReturn("png");
            var low=perspective("iso_S_90_lowres",4,90);var medium=perspective("iso_S_90_medres",8,90);var high=perspective("iso_S_90_hires",16,90);
            for(var p:java.util.List.of(low,medium,high))manager.hdmapman.perspectives.put(p.getName(),p);
            var shader=mock(HDShader.class);when(shader.getName()).thenReturn("stdtexture");manager.hdmapman.shaders.put("stdtexture",shader);
            var light=mock(HDLighting.class);when(light.getName()).thenReturn("default");manager.hdmapman.lightings.put("default",light);
            var original=new ConfigurationNode();original.put("name","flat");original.put("prefix","flat");original.put("perspective",low.getName());original.put("shader","stdtexture");
            var old=new HDMap(core,new ConfigurationNode(new java.util.LinkedHashMap<>(original.entries)));
            core.configuration.put("render-quality","balanced");
            var current=new HDMap(core,original);assertNotEquals(old.getPrefix(),current.getPrefix());assertEquals(8,current.getPerspective().getScale());
            assertTrue(current.getTilePreviewSources().contains(new MapType.TilePreviewSource(old.getPrefix(),-1,MapType.ImageEncoding.PNG)));
            assertTrue(current.getTilePreviewSources().contains(new MapType.TilePreviewSource("flat",-1,MapType.ImageEncoding.PNG)));
            assertTrue(current.getTilePreviewSources().contains(new MapType.TilePreviewSource("flat",1,MapType.ImageEncoding.JPG)));
            var reloaded=new HDMap(core,current.saveConfiguration());assertEquals(current.getPrefix(),reloaded.getPrefix());assertEquals(current.getTilePreviewSources(),reloaded.getTilePreviewSources());
            assertEquals(-1,TileLayout.signedCompatibleShift(high,medium));assertEquals(Integer.MIN_VALUE,TileLayout.signedCompatibleShift(high,perspective("iso_S_30_hires",16,30)));
        } finally {MapManager.mapman=previous;}
    }

}
