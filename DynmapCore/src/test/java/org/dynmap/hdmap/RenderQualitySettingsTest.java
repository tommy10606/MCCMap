package org.dynmap.hdmap;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import org.dynmap.ConfigurationNode;
import org.junit.jupiter.api.Test;

class RenderQualitySettingsTest {
    private final ConfigurationNode global = new ConfigurationNode();
    private ConfigurationNode map(String perspective, String shader) {
        var map = new ConfigurationNode();map.put("perspective",perspective);map.put("shader",shader);return map;
    }
    @Test void stockSurfaceDoublesLinearDetailAndRemovesDefaultJpegArtifacts() {
        var map=map("iso_SE_30_hires","stdtexture");
        assertTrue(RenderQualitySettings.apply(global,map,Set.of("iso_SE_30_vhires")));
        assertEquals("iso_SE_30_vhires",map.getString("perspective",""));assertEquals("png",map.getString("image-format",""));
        assertFalse(RenderQualitySettings.apply(global,map,Set.of("iso_SE_30_vhires")));
    }
    @Test void flatAndCaveMapsGainDetailWithoutChangingAngles() {
        var flat=map("iso_S_90_lowres","stdtexture");
        var cave=map("iso_SE_60_lowres","cave");
        RenderQualitySettings.apply(global,flat,Set.of("iso_S_90_hires"));RenderQualitySettings.apply(global,cave,Set.of("iso_SE_60_medres"));
        assertEquals("iso_S_90_hires",flat.getString("perspective",""));assertEquals("iso_SE_60_medres",cave.getString("perspective",""));
    }
    @Test void existingBoostKeepsItsAbsolutePeakResolution() {
        var map=map("iso_SE_30_hires","stdtexture");map.put("boostzoom",2);
        RenderQualitySettings.apply(global,map,Set.of("iso_SE_30_vhires"));assertEquals(1,map.getInteger("boostzoom",-1));
    }
    @Test void customAndHigherDetailPerspectivesAndExplicitFormatsSurvive() {
        var custom=map("my_custom_view","stdtexture");assertFalse(RenderQualitySettings.apply(global,custom,Set.of()));
        assertFalse(custom.containsKey("image-format"));
        var high=map("iso_SE_30_xhires","stdtexture");high.put("image-format","jpg-q100");
        assertFalse(RenderQualitySettings.apply(global,high,Set.of("iso_SE_30_vhires")));assertEquals("iso_SE_30_xhires",high.getString("perspective",""));assertEquals("jpg-q100",high.getString("image-format",""));
    }
    @Test void configuredProfileAndMissingTargetsDoNotChangeGeometry() {
        var map=map("iso_SE_30_hires","stdtexture");global.put("render-quality","configured");
        assertFalse(RenderQualitySettings.apply(global,map,Set.of("iso_SE_30_vhires")));
        global.put("render-quality","high");RenderQualitySettings.apply(global,map,Set.of());assertEquals("iso_SE_30_hires",map.getString("perspective",""));
    }
    @Test void readOnlyMapsKeepTheirDeclaredLayoutAndEncoding() {
        var map=map("iso_SE_30_hires","stdtexture");map.put("readonly",true);
        assertFalse(RenderQualitySettings.apply(global,map,Set.of("iso_SE_30_vhires")));
        assertEquals("iso_SE_30_hires",map.getString("perspective",""));assertFalse(map.containsKey("image-format"));
    }
    @Test void balancedProfileHalvesFormerStockResolutionAndDisablesHiddenBoostDetail() {
        global.put("render-quality","balanced");
        for(var input:new String[][]{{"iso_S_90_hires","stdtexture","iso_S_90_medres"},{"iso_SE_30_vhires","stdtexture","iso_SE_30_hires"},{"iso_SE_60_medres","cave","iso_SE_60_lowres"}}) {
            var config=map(input[0],input[1]);config.put("boostzoom",2);
            assertTrue(RenderQualitySettings.apply(global,config,Set.of(input[2])));assertEquals(input[2],config.getString("perspective",""));assertEquals(0,config.getInteger("boostzoom",-1));
            assertFalse(RenderQualitySettings.apply(global,config,Set.of(input[2])));
        }
    }

}
