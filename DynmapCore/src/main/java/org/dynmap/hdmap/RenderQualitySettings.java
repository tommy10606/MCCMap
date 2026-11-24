package org.dynmap.hdmap;

import java.util.Set;
import java.util.regex.Pattern;
import org.dynmap.ConfigurationNode;

/** Upgrade stock map geometry without changing view angles or custom perspectives. */
final class RenderQualitySettings {
    private static final Pattern STOCK = Pattern.compile("iso_([A-Z]+)_(30|60|90)_(vlowres|lowres|medres|hires|vhires|xhires)");
    static boolean apply(ConfigurationNode global, ConfigurationNode map, Set<String> available) {
        String profile = global == null ? "configured" : global.getString("render-quality", "high");
        boolean balanced = "balanced".equalsIgnoreCase(profile);
        if (map.getBoolean("readonly", false) || (!balanced && !"high".equalsIgnoreCase(profile))) return false;
        String original = map.getString("perspective", "default");
        var match = STOCK.matcher(original);
        if (!match.matches()) return false;
        int scale = switch (match.group(3)) {
            case "vlowres" -> 2; case "lowres" -> 4; case "medres" -> 8;
            case "hires" -> 16; case "vhires" -> 32; default -> 64;
        };
        int target = "cave".equals(map.getString("shader", "")) ? 8 : "90".equals(match.group(2)) ? 16 : 32;
        if(balanced) target /= 2;
        String suffix = target == 4 ? "lowres" : target == 8 ? "medres" : target == 16 ? "hires" : "vhires";
        String replacement = "iso_" + match.group(1) + "_" + match.group(2) + "_" + suffix;
        boolean changed = false;
        if ((balanced ? scale != target : scale < target) && available.contains(replacement)) {
            map.put("perspective", replacement);
            // Keep an existing boost region's absolute detail instead of multiplying it again.
            int boost = map.getInteger("boostzoom", 0);
            for (int size = scale; size < target; size *= 2) boost = Math.max(0, boost - 1);
            map.put("boostzoom", boost);
            changed = true;
        }
        if(balanced && map.getInteger("boostzoom",0)!=0 && available.contains(replacement)) {map.put("boostzoom",0);changed=true;}
        String format = map.getString("image-format", "default");
        if ("default".equals(format)) {map.put("image-format", "png"); changed = true;}
        return changed;
    }
}
