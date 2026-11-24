package org.dynmap;

import java.util.function.Consumer;

/** Report TPS transitions once, independently of ordinary progress messages. */
final class RenderTpsNotifications {
    private boolean paused;
    synchronized boolean wouldChange(boolean low) { return low != paused; }
    synchronized void update(boolean low, String render, String reason, Consumer<String> console, Consumer<String> player) {
        if (low == paused) return;
        paused = low;
        String message = (low ? "Paused " : "TPS pause cleared for ") + render + ": " + reason;
        console.accept(message);
        if (player != null) player.accept(message);
    }
}
