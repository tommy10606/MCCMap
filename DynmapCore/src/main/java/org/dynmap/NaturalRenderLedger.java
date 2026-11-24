package org.dynmap;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Durable completion and explicit cancellation, independent of the live render frontier. */
final class NaturalRenderLedger {
    private final Set<String> completed = new HashSet<>(), suspendedWorlds = new HashSet<>();
    NaturalRenderLedger(ConfigurationNode saved) {
        if (saved != null) {
            completed.addAll(saved.getStrings("completed", List.of()));
            suspendedWorlds.addAll(saved.getStrings("suspended-worlds", List.of()));
        }
    }
    static String key(DynmapWorld world, MapType map) {
        return world.getName() + "\n" + ProgressCheckpoint.layout(world) + "\n" + map.getName();
    }
    boolean eligible(DynmapWorld world, MapType map) {
        return !map.isReadOnly() && !suspendedWorlds.contains(world.getName()) && !completed.contains(key(world, map));
    }
    void complete(DynmapWorld world, MapType map) { completed.add(key(world, map)); }
    void suspend(String world) { suspendedWorlds.add(world); }
    boolean suspended(String world) { return suspendedWorlds.contains(world); }
    void resume() { suspendedWorlds.clear(); }
    static boolean reusable(org.dynmap.storage.MapStorageTile tile) {
        if (tile.zoom != 0) return false;
        for (var variant : tile.map.getVariants()) {
            var required = tile.world.getMapStorage().getTile(tile.world, tile.map, tile.x, tile.y, 0, variant);
            if (required == null) return false;
            try { if (!required.exists()) return false; }
            finally { required.cleanup(); }
        }
        return true;
    }
    ConfigurationNode save() {
        var saved = new ConfigurationNode();
        saved.put("completed", completed.stream().sorted().toList());
        saved.put("suspended-worlds", suspendedWorlds.stream().sorted().toList());
        return saved;
    }
}
