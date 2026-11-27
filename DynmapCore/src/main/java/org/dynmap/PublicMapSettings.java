package org.dynmap;
import java.util.*;
/** Add newly supported settings to existing installations without replacing choices. */
final class PublicMapSettings {
    static boolean migrate(ConfigurationNode config) {
        boolean changed=StaleTileRefreshSettings.migrate(config);
        if(!config.containsKey("background-task-log-interval-minutes")){config.put("background-task-log-interval-minutes",30);changed=true;}
        if(!config.containsKey("render-progress-log-interval-seconds")){config.put("render-progress-log-interval-seconds",60);changed=true;}
        changed|=defaults(config,"legacy-tile-cleanup",Map.of("enabled",true,"startup-delay-seconds",60,"checks-per-batch",64));
        changed|=defaults(config,"https",Map.of("enabled",false,"domain","","public-port",0,"redirect-http",true));
        var https=config.getNode("https");
        if(https!=null)changed|=defaults(https,"letsencrypt",Map.of("enabled",true,"agree-to-terms",false,"email","","staging",false,"challenge-bindaddress","0.0.0.0","challenge-port",8080));
        // One-time migration from this unreleased fork's former high-detail default.
        if(!config.containsKey("render-quality-settings-version")) {
            String profile=config.getString("render-quality","high");
            if("high".equalsIgnoreCase(profile))config.put("render-quality","balanced");
            config.put("render-quality-settings-version",2);changed=true;
        }
        if(!config.containsKey("skin-download-allowed-hosts")){config.put("skin-download-allowed-hosts",List.of());changed=true;}
        changed|=defaults(config,"progress-checkpoints",Map.of("enabled",true,"interval-seconds",60));
        changed|=defaults(config,"full-render-speed",Map.of("no-players-percent",125,"players-online-percent",25,"players-online-max-workers",1));
        changed|=defaults(config,"natural-render",Map.of("enabled",true,"players-online-percent",10,"startup-delay-seconds",60,"idle-seconds",10));
        if(!config.containsKey("online-render-speed-settings-version")) {
            var full=config.getNode("full-render-speed");var natural=config.getNode("natural-render");
            if(full!=null && full.getDouble("players-online-percent",25)==50)full.put("players-online-percent",25);
            if(natural!=null && natural.getDouble("players-online-percent",10)==25)natural.put("players-online-percent",10);
            config.put("online-render-speed-settings-version",2);changed=true;
        }
        changed|=defaults(config,"worldguard-regions",Map.of("fill-opacity",0.06));
        changed|=defaults(config,"web-map-zoom",Map.of("fit-world-border",true,"border-padding",1.1,"max-pixels-per-block",16));
        changed|=defaults(config,"essentials-warps",Map.of("enabled",true,"respect-per-warp-permissions",false,"refresh-interval-seconds",30,"allow-list",List.of(),"hide-list",List.of()));
        changed|=defaults(config,"player-chat",Map.of("venturechat-enabled",true,"venturechat-public-channels",List.of("Global")));
        return changed;
    }
    private static boolean defaults(ConfigurationNode config,String key,Map<String,Object> values) {
        if(!config.containsKey(key)){config.put(key,new LinkedHashMap<>(values));return true;}
        var node=config.getNode(key);if(node==null)return false;
        boolean changed=false;
        for(var entry:values.entrySet())if(!node.containsKey(entry.getKey())){node.put(entry.getKey(),entry.getValue());changed=true;}
        return changed;
    }
}
