package org.dynmap;

final class TpsSettings {
    static boolean migrate(ConfigurationNode config) {
        if (config.getInteger("tps-policy-version", 0) >= 1) return false;
        for (String key : new String[]{"update-min-tps","fullrender-min-tps","zoomout-min-tps"})
            if (config.getDouble(key, 15) == 18) config.put(key, 15.0);
        config.put("tps-policy-version", 1);
        return true;
    }
}
