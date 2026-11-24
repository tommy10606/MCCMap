// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap.bukkit.overlays;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.TreeSet;
import java.util.function.Function;
import com.sk89q.worldguard.domains.DefaultDomain;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.dynmap.DynmapCore;
import org.dynmap.Client;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.MarkerSet;

/** Native, non-persistent WorldGuard layer. Reads on the server thread; changes no regions. */
public final class WorldGuardRegionOverlay implements AutoCloseable {
    private static final String SET_ID = "mccmap.worldguard";
    private final JavaPlugin plugin;
    private final DynmapCore core;
    private final MarkerSet markers;
    private final Map<String, Shape> previous = new HashMap<>();
    private BukkitTask task;
    private boolean warned;
    record Point(double x, double z) {}
    record Shape(String label, String world, List<Point> points, int minY, int maxY, int priority,
                 int minX, int maxX, int minZ, int maxZ,
                 List<String> owners, List<String> members) {}

    public WorldGuardRegionOverlay(JavaPlugin plugin, DynmapCore core) {
        this.plugin = plugin;
        this.core = core;
        var api = core.getMarkerAPI();
        MarkerSet set = api.getMarkerSet(SET_ID);
        if (set == null) set = api.createMarkerSet(SET_ID, "WorldGuard Regions", null, false);
        if (set == null) throw new IllegalStateException("Cannot create native WorldGuard layer");
        markers = set;
        markers.setHideByDefault(false);
        long interval = Math.max(1, Math.min(3600, core.configuration.getInteger("worldguard-regions/refresh-interval-seconds", 10))) * 20L;
        refresh();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, interval, interval);
    }
    /** Cuboids include their maximum block; polygons retain the declared vertices. */
    static Shape shape(String world, ProtectedRegion region) {
        return shape(world, region, UUID::toString);
    }
    static Shape shape(String world, ProtectedRegion region, Function<UUID, String> names) {
        if (!region.isPhysicalArea()) return null; // The global region has no finite outline.
        List<Point> points;
        if (region instanceof ProtectedCuboidRegion) {
            var min = region.getMinimumPoint(); var max = region.getMaximumPoint();
            double minX = min.x(), minZ = min.z(), maxX = (double) max.x() + 1, maxZ = (double) max.z() + 1;
            points = List.of(new Point(minX, minZ), new Point(maxX, minZ), new Point(maxX, maxZ), new Point(minX, maxZ));
        } else if (region instanceof ProtectedPolygonalRegion polygon) {
            points = polygon.getPoints().stream().map(v -> new Point(v.x(), v.z())).toList();
        } else return null;
        if (points.size() < 3) return null;
        return new Shape(region.getId(), world, points, region.getMinimumPoint().y(), region.getMaximumPoint().y(), region.getPriority(),
            region.getMinimumPoint().x(), region.getMaximumPoint().x(), region.getMinimumPoint().z(), region.getMaximumPoint().z(),
            domain(region.getOwners(), names), domain(region.getMembers(), names));
    }
    private static List<String> domain(DefaultDomain domain, Function<UUID, String> names) {
        var entries = new TreeSet<String>(domain.getPlayers());
        domain.getGroups().forEach(group -> entries.add("group: " + group));
        domain.getUniqueIds().forEach(uuid -> {
            String name = names.apply(uuid);
            entries.add(name == null || name.isBlank() ? uuid.toString() : name);
        });
        return List.copyOf(entries);
    }
    static String description(Shape shape) {
        return "<b>" + Client.encodeForHTML(shape.label) + "</b><br>WorldGuard region<br>X: " + shape.minX + " to " + shape.maxX + "<br>Z: " + shape.minZ + " to " + shape.maxZ + "<br>Y: "
            + shape.minY + " to " + shape.maxY + "<br>Priority: " + shape.priority
            + "<br>Owners: " + Client.encodeForHTML(shape.owners.isEmpty() ? "None" : String.join(", ", shape.owners))
            + "<br>Members: " + Client.encodeForHTML(shape.members.isEmpty() ? "None" : String.join(", ", shape.members));
    }
    private void refresh() {
        if (!OverlayAvailability.worldGuard(Bukkit.getPluginManager())) { clear(); return; }
        try {
            var seen = new HashSet<String>();
            var container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            for (var world : Bukkit.getWorlds()) {
                if (core.getWorld(world.getName()) == null) continue;
                var manager = container.get(BukkitAdapter.adapt(world));
                if (manager == null) continue;
                for (ProtectedRegion region : manager.getRegions().values()) {
                    Shape shape = shape(world.getName(), region, uuid -> Bukkit.getOfflinePlayer(uuid).getName());
                    if (shape == null) continue;
                    String id = world.getUID() + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(region.getId().getBytes(StandardCharsets.UTF_8));
                    seen.add(id);
                    if (shape.equals(previous.get(id)) && markers.findAreaMarker(id) != null) continue;
                    double[] x = shape.points.stream().mapToDouble(Point::x).toArray();
                    double[] z = shape.points.stream().mapToDouble(Point::z).toArray();
                    AreaMarker marker = markers.findAreaMarker(id);
                    if (marker == null) marker = markers.createAreaMarker(id, shape.label, false, shape.world, x, z, false);
                    else { marker.setLabel(shape.label, false); marker.setCornerLocations(x, z); }
                    if (marker == null) continue;
                    marker.setLineStyle(2, 0.85, 0x3388ff);
                    marker.setFillStyle(Math.max(0, Math.min(1, core.configuration.getDouble("worldguard-regions/fill-opacity", 0.06))), 0x3388ff);
                    int bottom = Math.max(shape.minY, world.getMinHeight());
                    int top = Math.max(bottom, Math.min(shape.maxY, world.getMaxHeight() - 1));
                    // One filled footprint: stacked faces use even-odd fill and cancel interior hit areas.
                    double displayY = Math.max(bottom, Math.min(world.getSeaLevel(), top));
                    marker.setRangeY(displayY, displayY);
                    marker.setDescription(description(shape));
                    previous.put(id, shape);
                }
            }
            for (var it = previous.keySet().iterator(); it.hasNext();) {
                String id = it.next();
                if (!seen.contains(id)) { var marker = markers.findAreaMarker(id); if (marker != null) marker.deleteMarker(); it.remove(); }
            }
            warned = false;
        } catch (RuntimeException failure) {
            if (!warned) { plugin.getLogger().warning("Could not refresh the native WorldGuard layer; retrying next interval"); warned = true; }
        }
    }
    private void clear() {
        for (String id : previous.keySet()) { var marker = markers.findAreaMarker(id); if (marker != null) marker.deleteMarker(); }
        previous.clear();
    }
    @Override public void close() {
        if (task != null) task.cancel();
        markers.deleteMarkerSet();
        previous.clear();
    }
}
