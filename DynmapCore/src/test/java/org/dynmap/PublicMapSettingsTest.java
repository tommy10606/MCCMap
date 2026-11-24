package org.dynmap;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class PublicMapSettingsTest {
    @Test void upgradeAddsNewSettingsOnceWithoutReplacingExistingChoices(){var config=new ConfigurationNode();config.put("worldguard-regions",new LinkedHashMap<>(Map.of("fill-opacity",0.2,"enabled",false)));config.put("player-chat",new LinkedHashMap<>(Map.of("venturechat-public-channels",List.of("Public"))));assertTrue(PublicMapSettings.migrate(config));assertEquals(0.2,config.getDouble("worldguard-regions/fill-opacity",0));assertFalse(config.getBoolean("worldguard-regions/enabled",true));assertEquals(List.of("Public"),config.getStrings("player-chat/venturechat-public-channels",List.of()));assertTrue(config.getBoolean("web-map-zoom/fit-world-border",false));assertTrue(config.containsKey("essentials-warps"));assertFalse(config.getBoolean("essentials-warps/respect-per-warp-permissions",true));assertTrue(config.containsKey("skin-download-allowed-hosts"));assertEquals(125,config.getInteger("full-render-speed/no-players-percent",0));assertEquals(25,config.getInteger("full-render-speed/players-online-percent",0));assertFalse(PublicMapSettings.migrate(config));}
    @Test void oldHighDefaultMigratesOnceButLaterExplicitHighAndConfiguredChoicesSurvive() {
        var config=new ConfigurationNode();config.put("render-quality","high");assertTrue(PublicMapSettings.migrate(config));assertEquals("balanced",config.getString("render-quality",""));assertEquals(16,config.getInteger("web-map-zoom/max-pixels-per-block",0));
        config.put("render-quality","high");assertFalse(PublicMapSettings.migrate(config));assertEquals("high",config.getString("render-quality",""));
        var custom=new ConfigurationNode();custom.put("render-quality","configured");PublicMapSettings.migrate(custom);assertEquals("configured",custom.getString("render-quality",""));
    }

    @Test void olderOnlineDefaultsSlowDownOnceAndDoNotRaiseExistingSlowerChoices() {
        var config=new ConfigurationNode();config.put("full-render-speed",new LinkedHashMap<>(Map.of("players-online-percent",50,"no-players-percent",125)));config.put("natural-render",new LinkedHashMap<>(Map.of("players-online-percent",25)));
        assertTrue(PublicMapSettings.migrate(config));assertEquals(25,config.getInteger("full-render-speed/players-online-percent",0));assertEquals(10,config.getInteger("natural-render/players-online-percent",0));assertEquals(1,config.getInteger("full-render-speed/players-online-max-workers",0));assertEquals(125,config.getInteger("full-render-speed/no-players-percent",0));
        config.getNode("full-render-speed").put("players-online-percent",50);assertFalse(PublicMapSettings.migrate(config));assertEquals(50,config.getInteger("full-render-speed/players-online-percent",0));
        var slower=new ConfigurationNode();slower.put("full-render-speed",new LinkedHashMap<>(Map.of("players-online-percent",10)));slower.put("natural-render",new LinkedHashMap<>(Map.of("players-online-percent",5)));PublicMapSettings.migrate(slower);assertEquals(10,slower.getInteger("full-render-speed/players-online-percent",0));assertEquals(5,slower.getInteger("natural-render/players-online-percent",0));
    }

}
