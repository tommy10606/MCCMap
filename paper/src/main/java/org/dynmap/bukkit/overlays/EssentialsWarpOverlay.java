package org.dynmap.bukkit.overlays;

import java.util.*;
import java.nio.charset.StandardCharsets;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.dynmap.Client;
import org.dynmap.DynmapCore;
import org.dynmap.markers.MarkerSet;

/** Reads the optional EssentialsX public API on the server thread. Never reads homes. */
public final class EssentialsWarpOverlay implements AutoCloseable {
    private final DynmapCore core;
    private final JavaPlugin owner;
    private final MarkerSet markers;
    private final BukkitTask task;
    private boolean warned;
    public EssentialsWarpOverlay(JavaPlugin owner, DynmapCore core) {
        this.owner=owner;this.core=core;
        var api=core.getMarkerAPI();var set=api.getMarkerSet("mccmap.essentials.warps");
        if(set==null)set=api.createMarkerSet("mccmap.essentials.warps","Warps",null,false);
        if(set==null)throw new IllegalStateException("Cannot create warp layer");
        markers=set;refresh();
        task=Bukkit.getScheduler().runTaskTimer(owner,this::refresh,200,Math.max(5,core.configuration.getInteger("essentials-warps/refresh-interval-seconds",30))*20L);
    }
    public static boolean publish(String name, boolean restricted, Collection<String> allowed, Collection<String> hidden) {
        if(hidden.stream().anyMatch(n->n.equalsIgnoreCase(name)))return false;
        if(!allowed.isEmpty())return allowed.stream().anyMatch(n->n.equalsIgnoreCase(name));
        return !restricted;
    }
    private void refresh() {
        var seen=new HashSet<String>();
        try {
            var plugin=Bukkit.getPluginManager().getPlugin("Essentials");
            if(plugin!=null&&plugin.isEnabled()) {
                Object warps=plugin.getClass().getMethod("getWarps").invoke(plugin);
                boolean restricted=false;
                if(core.configuration.getBoolean("essentials-warps/respect-per-warp-permissions",false)) {
                    Object settings=plugin.getClass().getMethod("getSettings").invoke(plugin);
                    restricted=(Boolean)settings.getClass().getMethod("getPerWarpPermission").invoke(settings);
                }
                var allowed=core.configuration.getStrings("essentials-warps/allow-list",List.of());
                var hidden=core.configuration.getStrings("essentials-warps/hide-list",List.of());
                var names=(Collection<?>)warps.getClass().getMethod("getList").invoke(warps);
                int count=0;
                for(Object value:names) {
                    if(++count>10000)break;
                    if(!(value instanceof String name)||!publish(name,restricted,allowed,hidden))continue;
                    Location loc=(Location)warps.getClass().getMethod("getWarp",String.class).invoke(warps,name);
                    if(loc==null||loc.getWorld()==null||core.getWorld(loc.getWorld().getName())==null||!Double.isFinite(loc.getX())||!Double.isFinite(loc.getY())||!Double.isFinite(loc.getZ()))continue;
                    String id=Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8));seen.add(id);
                    var marker=markers.findMarker(id);
                    var icon=core.getMarkerAPI().getMarkerIcon("portal");if(icon==null)icon=core.getMarkerAPI().getMarkerIcon("default");
                    if(marker==null)marker=markers.createMarker(id,name,false,loc.getWorld().getName(),loc.getX(),loc.getY(),loc.getZ(),icon,false);
                    else {marker.setLabel(name,false);marker.setLocation(loc.getWorld().getName(),loc.getX(),loc.getY(),loc.getZ());}
                    if(marker!=null)marker.setDescription(description(name,loc.getWorld().getName(),loc.getBlockX(),loc.getBlockY(),loc.getBlockZ()));
                }
            }
            warned=false;
        }catch(ReflectiveOperationException|LinkageError|RuntimeException failure) {
            seen.clear();
            if(!warned){owner.getLogger().warning("EssentialsX warp layer offline; compatible public API required");warned=true;}
        }
        for(var marker:markers.getMarkers())if(!seen.contains(marker.getMarkerID()))marker.deleteMarker();
    }
    static String description(String name,String world,int x,int y,int z) {
        return "<b>"+Client.encodeForHTML(name)+"</b><br>EssentialsX warp<br>World: "+Client.encodeForHTML(world)+"<br>X: "+x+" Y: "+y+" Z: "+z;
    }
    @Override public void close(){task.cancel();markers.deleteMarkerSet();}
}
