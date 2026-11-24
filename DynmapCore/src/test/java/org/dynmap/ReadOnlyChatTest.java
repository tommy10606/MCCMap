// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.dynmap.common.DynmapListenerManager;
import org.dynmap.common.DynmapListenerManager.EventType;
import org.dynmap.common.DynmapPlayer;
import org.dynmap.common.DynmapServerInterface;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class ReadOnlyChatTest {
    private DynmapCore core;
    private DynmapPlayer player;
    @Before public void setup() {
        core = new DynmapCore();
        core.setServer(mock(DynmapServerInterface.class));
        core.mapManager = mock(MapManager.class);
        player = mock(DynmapPlayer.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getName()).thenReturn("Thomas");
        when(player.getDisplayName()).thenReturn("Thomas");
        ConfigurationNode configuration = new ConfigurationNode();
        configuration.put("allowchat", true);
        new SimpleWebChatComponent(core, configuration);
    }
    @Test public void minecraftChatIsRelayedToBrowser() {
        core.listenerManager.processChatEvent(EventType.PLAYER_CHAT, player, "Hello from Minecraft <script>");
        ArgumentCaptor<Client.Update> update = ArgumentCaptor.forClass(Client.Update.class);
        verify(core.mapManager).pushUpdate(update.capture());
        Client.ChatMessage chat = (Client.ChatMessage) update.getValue();
        assertEquals("player", chat.source);
        assertEquals("Thomas", chat.playerId);
        assertEquals("Hello from Minecraft <script>", chat.message);
    }
    @Test public void legacyWebChatEventCannotBroadcastToMinecraft() {
        assertFalse(core.events.events.containsKey("webchat"));
        core.events.trigger("webchat", "cannot send");
        verifyNoInteractions(core.mapManager);
    }
    @Test public void offlinePlayersCannotPublishChat() {
        when(player.isOnline()).thenReturn(false);
        core.listenerManager.processChatEvent(EventType.PLAYER_CHAT, player, "offline");
        verifyNoInteractions(core.mapManager);
    }
    @Test public void nullPlayerCannotPublishChat() {
        core.listenerManager.processChatEvent(EventType.PLAYER_CHAT, null, "console");
        verifyNoInteractions(core.mapManager);
    }
    @Test public void noPublicChatInjectionAPIExists() {
        for (Class<?> type : new Class<?>[] { DynmapCommonAPI.class, DynmapCore.class }) {
            for (var method : type.getMethods()) {
                assertFalse(method.getName().equals("sendBroadcastToWeb"));
                assertFalse(method.getName().equals("postPlayerMessageToWeb"));
                assertFalse(method.getName().equals("postPlayerJoinQuitToWeb"));
            }
        }
    }
    @Test public void disablingChatRelayStillWorks() {
        core.disable_chat_to_web = true;
        core.listenerManager.processChatEvent(EventType.PLAYER_CHAT, player, "hidden");
        verifyNoInteractions(core.mapManager);
    }
}
