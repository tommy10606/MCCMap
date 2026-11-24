// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.HashMap;
import java.util.List;
import org.dynmap.common.DynmapPlayer;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.Test;

/** Removing web viewers must never bypass global player privacy controls. */
public class PublicPlayerVisibilityTest {
    private JSONObject publish(String option, boolean hidden) {
        DynmapCore core = new DynmapCore();
        core.setServer(mock(org.dynmap.common.DynmapServerInterface.class));
        core.mapManager = mock(MapManager.class);
        MapManager previous = MapManager.mapman;
        MapManager.mapman = core.mapManager;
        try {
            DynmapWorld world = mock(DynmapWorld.class, withSettings().useConstructor("world", 320, 63, -64)
                .defaultAnswer(CALLS_REAL_METHODS));
            world.sendposition = true;
            world.sendhealth = true;
            core.mapManager.worldsLookup = new HashMap<>();
            core.mapManager.worldsLookup.put("world", world);
            when(core.mapManager.getWorld("world")).thenReturn(world);
            when(core.mapManager.getWorldUpdates(anyString(), anyLong())).thenReturn(new Client.Update[0]);
            core.playerList = mock(PlayerList.class);
            DynmapPlayer player = mock(DynmapPlayer.class);
            when(player.getName()).thenReturn("Thomas");
            when(player.getDisplayName()).thenReturn("Thomas");
            when(player.getLocation()).thenReturn(new DynmapLocation("world", 13, 64, 29));
            when(player.getHealth()).thenReturn(20.0);
            when(core.playerList.getVisiblePlayers()).thenReturn(List.of(player));
            when(core.playerList.getHiddenPlayers()).thenReturn(List.of());
            ConfigurationNode config = new ConfigurationNode();
            config.put(option, hidden);
            config.put("sendhealth", true);
            config.put("sendposition", true);
            java.util.Map<String, Object> root = new java.util.HashMap<>();
            root.put("components", java.util.List.of(config.entries));
            LegacyWebSettings.removeFrom(root);
            new ClientUpdateComponent(core, config);
            JSONObject update = new JSONObject();
            core.events.trigger("buildclientupdate", new ClientUpdateEvent(0, world, update));
            return (JSONObject) ((JSONArray) update.get("players")).get(0);
        } finally { MapManager.mapman = previous; }
    }
    @Test public void publicPlayerPositionsRemainAvailableWhenEnabled() {
        JSONObject player = publish("hide-player-info", false);
        assertEquals(13.0, player.get("x"));
        assertEquals("Thomas", player.get("playerId"));
        assertFalse(player.containsKey("account"));
    }
    @Test public void globalPlayerPrivacyHidesPositionsAndHealth() {
        JSONObject player = publish("hide-player-info", true);
        assertEquals(0.0, player.get("x"));
        assertEquals(0, player.get("health"));
    }
    @Test public void legacyPrivacyFlagRemainsFailClosedWithoutWebAccounts() {
        JSONObject player = publish("protected-player-info", true);
        assertEquals(0.0, player.get("x"));
        assertEquals(0, player.get("health"));
    }
}
