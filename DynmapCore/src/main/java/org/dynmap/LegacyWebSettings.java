// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap;

import java.util.Map;

/** One-time removal of settings belonging to retired web-account and message-submission features. */
final class LegacyWebSettings {
    private LegacyWebSettings() { }
    static boolean removeFrom(Map<String, Object> config) {
        boolean changed = remove(config, "login-enabled", "login-required", "persist-ids-by-ip", "trusted-proxies",
            "webprefix", "websuffix", "webmsgformat", "spammessage", "joinmessage", "quitmessage");
        changed |= rename(config, "max-sessions", "max-connections");
        changed |= cleanNested(config.get("url"), "login", "register", "sendmessage");
        changed |= cleanNested(config.get("msg"), "chatrequireslogin", "chatnotallowed", "hiddennamejoin", "hiddennamequit");
        if (config.get("components") instanceof Iterable<?> components) {
            for (Object entry : components) {
                if (!(entry instanceof Map<?, ?> raw)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> component = (Map<String, Object>) raw;
                changed |= remove(component, "allowwebchat", "webchat-interval", "hidewebchatip", "trustclientname",
                    "use-player-login-ip", "require-player-login-ip", "block-banned-player-chat", "webchat-requires-login",
                    "webchat-permissions", "chatlengthlimit", "allowurlname", "sendbutton");
                changed |= rename(component, "protected-player-info", "hide-player-info");
            }
        }
        changed |= removeWorldAccess(config);
        return changed;
    }
    static boolean removeWorldAccess(Map<String, Object> config) {
        boolean changed = false;
        if (config.get("worlds") instanceof Iterable<?> worlds) {
            for (Object entry : worlds) {
                if (!(entry instanceof Map<?, ?> raw)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> world = (Map<String, Object>) raw;
                changed |= remove(world, "protected");
                if (world.get("maps") instanceof Iterable<?> maps) for (Object map : maps) {
                    if (map instanceof Map<?, ?> values) changed |= values.remove("protected") != null;
                }
            }
        }
        return changed;
    }
    private static boolean remove(Map<String, Object> map, String... keys) {
        boolean changed = false;
        for (String key : keys) if (map.containsKey(key)) { map.remove(key); changed = true; }
        return changed;
    }
    private static boolean cleanNested(Object value, String... keys) {
        if (!(value instanceof Map<?, ?> map)) return false;
        @SuppressWarnings("unchecked") Map<String, Object> nested = (Map<String, Object>) map;
        return remove(nested, keys);
    }
    private static boolean rename(Map<String, Object> map, String oldKey, String newKey) {
        if (!map.containsKey(oldKey)) return false;
        Object value = map.remove(oldKey);
        map.putIfAbsent(newKey, value);
        return true;
    }
}
