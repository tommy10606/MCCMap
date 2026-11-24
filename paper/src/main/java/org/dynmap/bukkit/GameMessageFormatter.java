package org.dynmap.bukkit;

import java.util.Locale;

/** Shared legacy styling for command, progress and diagnostic messages to players. */
final class GameMessageFormatter {
    static String format(String message) {
        if (message == null || message.isEmpty()) return message;
        if (message.indexOf('\u00a7') >= 0) return message;
        String text = message.toLowerCase(Locale.ROOT);
        String color = text.contains("error") || text.contains("failed") || text.startsWith("cannot ") || text.startsWith("invalid ") || text.contains("permission") ? "\u00a7c"
            : text.contains("paused") || text.contains("disabled") || text.contains("warning") || text.contains("cancel") ? "\u00a7e"
            : text.contains("finished") || text.contains("completed") || text.contains("enabled") || text.contains("success") ? "\u00a7a" : "\u00a77";
        String styled = message.replaceAll("(?<![\\w])(/[a-zA-Z][a-zA-Z0-9_-]*)", "\u00a7b$1" + color)
            .replaceAll("(?<![\\w])(-?\\d+(?:\\.\\d+)?(?:%|ms|GB|GiB)?)(?![\\w])", "\u00a7f$1" + color);
        return "\u00a78[\u00a7bMCCMap\u00a78] " + color + styled + "\u00a7r";
    }
}
