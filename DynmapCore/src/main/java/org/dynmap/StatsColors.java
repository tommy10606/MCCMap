package org.dynmap;

/** Restrained legacy colors supported by Minecraft players and the server console. */
final class StatsColors {
    static String format(String text) {
        if (!text.startsWith("  ") && text.endsWith(":")) return "\u00a7b\u00a7l" + text + "\u00a7r";
        int separator = text.indexOf(':');
        if (separator < 0) return "\u00a77" + text + "\u00a7r";
        String value = text.substring(separator + 1);
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        String color = lower.contains("failed") || lower.contains("unavailable") || lower.contains("cap reached") ? "\u00a7c"
            : lower.contains("waiting") || lower.contains("paused") || lower.contains("low tps") || lower.contains("manual") || lower.contains("active until") || lower.contains("bypassed") ? "\u00a7e" : "\u00a7a";
        return "\u00a77" + text.substring(0, separator + 1) + color + value + "\u00a7r";
    }
}
