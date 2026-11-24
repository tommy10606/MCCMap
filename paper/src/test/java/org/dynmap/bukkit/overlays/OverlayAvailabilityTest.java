package org.dynmap.bukkit.overlays;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.Set;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
class OverlayAvailabilityTest {
    private PluginManager plugins(Set<String> running) {
        return (PluginManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PluginManager.class},(p,m,a)->m.getName().equals("isPluginEnabled")&&running.contains(a[0]));
    }
    @Test void missingOrDisabledDependenciesLeaveTheOverlayOffline() {
        assertFalse(OverlayAvailability.worldGuard(plugins(Set.of())));
        assertFalse(OverlayAvailability.worldGuard(plugins(Set.of("WorldGuard"))));
        assertFalse(OverlayAvailability.worldGuard(plugins(Set.of("WorldEdit"))));
    }
    @Test void bothPluginsMustBeRunning() {assertTrue(OverlayAvailability.worldGuard(plugins(Set.of("WorldGuard","WorldEdit"))));}
}
