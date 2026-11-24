// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap.bukkit;

import static org.junit.Assert.*;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.Test;

public class PlayerChatRelayTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Component TEXT = Component.text("player message");
    private AsyncChatEvent event(boolean online, SignedMessage message, boolean cancelled) {
        Player player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isOnline" -> online;
                case "getUniqueId" -> PLAYER;
                case "getName", "toString" -> "Player";
                default -> null;
            });
        var event = new AsyncChatEvent(true, player, Set.of(), ChatRenderer.defaultRenderer(), TEXT, TEXT, message);
        event.setCancelled(cancelled);
        return event;
    }
    private SignedMessage fromPlayer(UUID sender) {
        return new SignedMessage() {
            public Instant timestamp() { return Instant.EPOCH; }
            public long salt() { return 0; }
            public Signature signature() { return null; }
            public Component unsignedContent() { return TEXT; }
            public String message() { return "player message"; }
            public Identity identity() { return Identity.identity(sender); }
        };
    }
    @Test public void onlinePlayerChatIsAccepted() {
        assertTrue(PlayerChatRelay.shouldRelay(event(true, fromPlayer(PLAYER), false)));
    }
    @Test public void essentialsProcessedMessageIsRelayedWithoutOriginalTextOrFormatting() {
        var event=event(true,fromPlayer(PLAYER),false);
        event.message(Component.text("processed message",net.kyori.adventure.text.format.NamedTextColor.GREEN));
        assertTrue(PlayerChatRelay.shouldRelay(event));
        assertEquals("processed message",PlayerChatRelay.message(event));
    }
    @Test public void unsignedPlayerChatIsAcceptedWhenItsPlayerIdentityMatches() {
        var event=event(true,fromPlayer(PLAYER),false);
        assertNull(event.signedMessage().signature());assertTrue(PlayerChatRelay.shouldRelay(event));
    }
    @Test public void cancelledChatIsRejected() {
        assertFalse(PlayerChatRelay.shouldRelay(event(true, fromPlayer(PLAYER), true)));
    }
    @Test public void offlinePlayerChatIsRejected() {
        assertFalse(PlayerChatRelay.shouldRelay(event(false, fromPlayer(PLAYER), false)));
    }
    @Test public void systemOrPluginBroadcastIsRejectedEvenWithAPlayerAttached() {
        assertFalse(PlayerChatRelay.shouldRelay(event(true, SignedMessage.system("plugin message", TEXT), false)));
    }
    @Test public void mismatchedPlayerIdentityIsRejected() {
        assertFalse(PlayerChatRelay.shouldRelay(event(true, fromPlayer(UUID.randomUUID()), false)));
    }
}
