// Modified for MCCMap: read-only compatibility adapter, independently implemented (2026).
package org.dynmap.bukkit.overlays;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.dynmap.DynmapCore;
import org.dynmap.markers.MarkerSet;

/** Native visualization of a running WorldBorder plugin, with no protection/configuration changes. */
public final class WorldBorderOverlay implements AutoCloseable {
    private final JavaPlugin owner;
    private final DynmapCore core;
    private final Map<String, WorldBorderReader.Border> previous = new HashMap<>();
    private MarkerSet markers;
    private Plugin source;
    private WorldBorderReader reader;
    private BukkitTask task;
    private boolean warned;

    public WorldBorderOverlay(JavaPlugin owner, DynmapCore core) {
        this.owner = owner; this.core = core;
        refresh();
        long interval = Math.max(1, Math.min(3600, core.configuration.getInteger("world-border/refresh-interval-seconds", 5))) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(owner, this::refresh, interval, interval);
    }
    private void refresh() {
        Plugin available = Bukkit.getPluginManager().getPlugin("WorldBorder");
        boolean vanilla = available == null || !available.isEnabled();
        if (vanilla) { source = null; reader = null; }
        try {
            if (!vanilla && (available != source || reader == null)) { reader = WorldBorderReader.bind(available); source = available; }
            var seen = new HashSet<String>();
            for (var world : Bukkit.getWorlds()) {
                var mapped = core.getWorld(world.getName());
                if (mapped == null || !mapped.showborder) continue;
                var border = vanilla ? readVanilla(world) : reader.read(world.getName());
                if (border == null) continue;
                if (markers == null) {
                    markers = core.getMarkerAPI().getMarkerSet("mccmap.worldborder");
                    if (markers == null) markers = core.getMarkerAPI().createMarkerSet("mccmap.worldborder", "World Border", null, false);
                    if (markers == null) throw new IllegalStateException("Native WorldBorder layer unavailable");
                }
                String id = world.getUID().toString();
                seen.add(id);
                if (border.equals(previous.get(id)) && (border.round() ? markers.findCircleMarker(id) != null : markers.findAreaMarker(id) != null)) continue;
                remove(id);
                if (border.round()) {
                    var marker = markers.createCircleMarker(id, "World Border", false, world.getName(), border.x(), 64, border.z(), border.radiusX(), border.radiusZ(), false);
                    if (marker == null) continue;
                    marker.setLineStyle(3, 0.85, 0xff0000); marker.setFillStyle(0, 0);
                } else {
                    double minX = border.x() - border.radiusX(), maxX = border.x() + border.radiusX();
                    double minZ = border.z() - border.radiusZ(), maxZ = border.z() + border.radiusZ();
                    var marker = markers.createAreaMarker(id, "World Border", false, world.getName(),
                        new double[]{minX, maxX, maxX, minX}, new double[]{minZ, minZ, maxZ, maxZ}, false);
                    if (marker == null) continue;
                    marker.setLineStyle(3, 0.85, 0xff0000); marker.setFillStyle(0, 0);
                }
                previous.put(id, border);
            }
            for (var it = previous.keySet().iterator(); it.hasNext();) {
                String id = it.next();
                if (!seen.contains(id)) { remove(id); it.remove(); }
            }
            warned = false;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            clear(); reader = null;
            if (!warned) { owner.getLogger().warning("WorldBorder layer offline: compatible public border API unavailable; map remains enabled"); warned = true; }
        }
    }
    static WorldBorderReader.Border readVanilla(org.bukkit.World world) {
        try {
            var polygon = org.dynmap.bukkit.helper.WorldBorderGeometry.from(world.getWorldBorder());
            if (polygon == null) return null;
            var min = polygon.getVertex(0);
            var max = polygon.getVertex(2);
            return new WorldBorderReader.Border((min.x + max.x) / 2, (min.y + max.y) / 2,
                (max.x - min.x) / 2, (max.y - min.y) / 2, false);
        } catch (LinkageError | RuntimeException unavailable) { return null; }
    }
    public static org.dynmap.utils.Polygon renderBorder(org.bukkit.World world) {
        try {
            Plugin plugin=Bukkit.getPluginManager().getPlugin("WorldBorder");
            if(plugin!=null&&plugin.isEnabled()) {
                var border=WorldBorderReader.bind(plugin).read(world.getName());
                if(border!=null) {
                    var polygon=new org.dynmap.utils.Polygon();int count=border.round()?64:4;
                    if(border.round())for(int i=0;i<count;i++) {
                        double angle=i*2*Math.PI/count;
                        // Circumscribe the ellipse so boundary slivers are included in the check.
                        double cover=1/Math.cos(Math.PI/count);
                        polygon.addVertex(border.x()+cover*border.radiusX()*Math.cos(angle),border.z()+cover*border.radiusZ()*Math.sin(angle));
                    } else {
                        polygon.addVertex(border.x()-border.radiusX(),border.z()-border.radiusZ());
                        polygon.addVertex(border.x()+border.radiusX(),border.z()-border.radiusZ());
                        polygon.addVertex(border.x()+border.radiusX(),border.z()+border.radiusZ());
                        polygon.addVertex(border.x()-border.radiusX(),border.z()+border.radiusZ());
                    }
                    return polygon;
                }
            }
        }catch(ReflectiveOperationException|LinkageError|RuntimeException unavailable){ /* Optional provider; native fallback. */ }
        return org.dynmap.bukkit.helper.WorldBorderGeometry.from(world.getWorldBorder());
    }
    private void remove(String id) {
        if (markers == null) return;
        var area = markers.findAreaMarker(id); if (area != null) area.deleteMarker();
        var circle = markers.findCircleMarker(id); if (circle != null) circle.deleteMarker();
    }
    private void clear() { for (String id : previous.keySet()) remove(id); previous.clear(); }
    @Override public void close() {
        if (task != null) task.cancel();
        if (markers != null) markers.deleteMarkerSet();
        previous.clear(); reader = null; source = null;
    }
}
