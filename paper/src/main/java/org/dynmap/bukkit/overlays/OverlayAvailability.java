// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap.bukkit.overlays;
import org.bukkit.plugin.PluginManager;

/** Check the running plugins before resolving any optional API classes. */
public final class OverlayAvailability {
    public static boolean worldGuard(PluginManager plugins) {
        return plugins.isPluginEnabled("WorldGuard") && plugins.isPluginEnabled("WorldEdit");
    }
    private OverlayAvailability() {}
}
