package org.dynmap;

import java.util.Locale;

/** Recent, paced wall-clock throughput. Samples are discarded across pauses/restarts. */
final class RenderProgress {
    private long sampledAt = -1, sampledCount, windowStart, windowCount;
    private double tilesPerMillis;
    private int presence = -1;

    synchronized void sample(long count, long now, boolean players) {
        int currentPresence = players ? 1 : 0;
        if (sampledAt < 0 || presence != currentPresence || count < sampledCount) {
            presence = currentPresence;
            sampledAt = windowStart = now;
            sampledCount = windowCount = count;
            tilesPerMillis = 0;
            return;
        }
        if (now - sampledAt >= 5000) {
            double rate = (double)(count - sampledCount) / (now - sampledAt);
            tilesPerMillis = tilesPerMillis == 0 ? rate : tilesPerMillis * 0.5 + rate * 0.5;
            sampledAt = now;
            sampledCount = count;
        }
    }

    synchronized void reset() { sampledAt = -1; tilesPerMillis = 0; }

    synchronized String describe(long completed, long estimatedTotal, long now, String pause) {
        String progress = estimatedTotal > 0
            ? String.format(Locale.ROOT, "~%.1f%% (%d / ~%d base tiles)", Math.min(99.9, 100.0 * completed / Math.max(estimatedTotal, completed)), completed, estimatedTotal)
            : completed + " base tiles; percentage unavailable";
        if (pause != null) return progress + "; ETA paused (" + pause + ")";
        if (estimatedTotal <= 0) return progress + "; ETA unavailable";
        if (completed >= estimatedTotal) return progress + "; ETA finishing/discovering additional tiles";
        if (sampledAt < 0 || now - windowStart < 5000 || completed <= windowCount || tilesPerMillis <= 0)
            return progress + "; ETA calculating";
        long remaining = (long)Math.ceil((estimatedTotal - completed) / tilesPerMillis / 1000.0);
        return progress + "; ~" + duration(remaining) + " remaining";
    }

    static String duration(long seconds) {
        seconds = Math.max(1, seconds);
        if (seconds >= 86400) return seconds / 86400 + "d " + seconds % 86400 / 3600 + "h";
        if (seconds >= 3600) return seconds / 3600 + "h " + seconds % 3600 / 60 + "m";
        if (seconds >= 60) return seconds / 60 + "m " + seconds % 60 + "s";
        return seconds + "s";
    }
}
