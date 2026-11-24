// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class LegacyWebSettingsTest {
    @Test public void obsoleteSettingsAreDeletedWhileStorageAndPlayerPrivacySurvive() {
        Map<String, Object> config = new HashMap<>();
        config.put("login-enabled", true);
        config.put("login-required", true);
        config.put("persist-ids-by-ip", true);
        config.put("max-sessions", 47);
        Map<String, Object> urls = new HashMap<>(Map.of("login", "up/login", "register", "up/register", "sendmessage", "up/sendmessage", "tiles", "tiles/"));
        config.put("url", urls);
        Map<String, Object> storage = new HashMap<>(Map.of("type", "mysql", "userid", "mapstorage", "password", "database-secret"));
        config.put("storage", storage);
        Map<String, Object> component = new HashMap<>(Map.of("allowwebchat", true, "allowchat", true, "protected-player-info", true));
        config.put("components", new ArrayList<>(List.of(component)));
        assertTrue(LegacyWebSettings.removeFrom(config));
        assertFalse(config.containsKey("login-enabled"));
        assertFalse(config.containsKey("login-required"));
        assertFalse(config.containsKey("persist-ids-by-ip"));
        assertEquals(47, config.get("max-connections"));
        assertEquals(Map.of("tiles", "tiles/"), urls);
        assertEquals("database-secret", storage.get("password"));
        assertEquals(true, component.get("allowchat"));
        assertEquals(true, component.get("hide-player-info"));
        assertFalse(component.containsKey("protected-player-info"));
        assertFalse(component.containsKey("allowwebchat"));
        assertFalse(LegacyWebSettings.removeFrom(config));
    }
    @Test public void explicitNewSettingsTakePrecedenceDuringMigration() {
        Map<String, Object> config = new HashMap<>(Map.of("max-sessions", 47, "max-connections", 32));
        assertTrue(LegacyWebSettings.removeFrom(config));
        assertEquals(32, config.get("max-connections"));
    }
    @Test public void obsoleteWorldAccessFlagsAreRemovedWithoutChangingRenderSettings() {
        Map<String, Object> map = new HashMap<>(Map.of("protected", true, "name", "surface"));
        Map<String, Object> world = new HashMap<>(Map.of("protected", true, "name", "world", "maps", List.of(map)));
        Map<String, Object> config = new HashMap<>(Map.of("worlds", List.of(world)));
        assertTrue(LegacyWebSettings.removeWorldAccess(config));
        assertFalse(world.containsKey("protected"));
        assertEquals(Map.of("name", "surface"), map);
        assertFalse(LegacyWebSettings.removeWorldAccess(config));
    }
}
