package org.dynmap.bukkit.overlays;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.junit.jupiter.api.Test;

class WorldBorderFallbackTest {
    private World world(double x, double z, double size) {
        WorldBorder border = (WorldBorder) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{WorldBorder.class}, (p, m, a) -> switch (m.getName()) {
                case "getCenter" -> new Location(null, x, 0, z);
                case "getSize" -> size;
                case "getMaxCenterCoordinate" -> 29999984.0;
                default -> throw new UnsupportedOperationException();
            });
        return (World) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{World.class},
            (p, m, a) -> { if (m.getName().equals("getWorldBorder")) return border;
                throw new UnsupportedOperationException(); });
    }
    @Test void usesNativeCenterAndSizeAndTracksChanges() {
        assertEquals(new WorldBorderReader.Border(100, -50, 250, 250, false),
            WorldBorderOverlay.readVanilla(world(100, -50, 500)));
        assertEquals(new WorldBorderReader.Border(20, 30, 0.5, 0.5, false),
            WorldBorderOverlay.readVanilla(world(20, 30, 1)));
    }
    @Test void clampsToMinecraftLimits() {
        var border = WorldBorderOverlay.readVanilla(world(29999980, 0, 100));
        assertEquals(29999984, border.x() + border.radiusX());
        assertFalse(border.round());
    }
    @Test void invalidOrUnavailableNativeBorderStaysOffline() {
        assertNull(WorldBorderOverlay.readVanilla(world(0, 0, Double.NaN)));
        assertNull(WorldBorderOverlay.readVanilla(null));
    }
}
