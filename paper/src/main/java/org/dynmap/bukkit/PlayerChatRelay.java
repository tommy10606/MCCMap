// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap.bukkit;

import io.papermc.paper.event.player.AsyncChatEvent;

/** Accept player chat, excluding cancelled, offline, system, and mismatched-sender events. */
final class PlayerChatRelay {
    private PlayerChatRelay() { }
    // Read the final message after EssentialsX Chat/moderation, without player prefixes or renderers.
    static String message(AsyncChatEvent event) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.message());
    }
    static boolean shouldRelay(AsyncChatEvent event) {
        var player = event.getPlayer();
        var message = event.signedMessage();
        return !event.isCancelled() && player.isOnline() && message != null && !message.isSystem()
            && player.getUniqueId().equals(message.identity().uuid());
    }
}
