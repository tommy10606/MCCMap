package org.dynmap;

import java.util.LinkedHashMap;
import java.util.Map;

/** Upgrade the former fixed-rate refresh defaults once, preserving custom ages. */
final class StaleTileRefreshSettings {
    static boolean migrate(ConfigurationNode config) {
        if (!config.containsKey("stale-tile-refresh")) {
            config.put("stale-tile-refresh", new LinkedHashMap<>(Map.of("enabled", true,
                "max-age-days", 60, "render-speed-percent", 10, "scan-interval-hours", 24,
                "startup-delay-seconds", 300, "max-tiles-per-scan", 4096, "backlog-limit", 20)));
            return true;
        }
        var settings = config.getNode("stale-tile-refresh");
        if (settings == null) return false;
        boolean changed = false;
        if (!settings.containsKey("render-speed-percent")) {
            if (settings.getInteger("max-age-days", 30) == 30) settings.put("max-age-days", 60);
            settings.put("render-speed-percent", 10);
            changed = true;
        }
        if (!settings.containsKey("max-age-days")) { settings.put("max-age-days", 60); changed = true; }
        if (settings.containsKey("tiles-per-minute")) { settings.remove("tiles-per-minute"); changed = true; }
        return changed;
    }
    static double speedPercent(double configured) {
        return Double.isFinite(configured) && configured >= 1 && configured <= 100 ? configured : 10;
    }
    /** Delay after a tile: target a fraction of the configured full-render throughput. */
    static long cooldownNanos(long workNanos, long sliceNanos, int parallel, double percent) {
        double work = Math.max(1, workNanos);
        double normalPeriod = Math.max(work, Math.max(0, sliceNanos)) / Math.max(1, parallel);
        double delay = Math.ceil(normalPeriod * 100 / speedPercent(percent) - work);
        return (long)Math.max(0, Math.min(Long.MAX_VALUE / 4.0, delay));
    }
}
