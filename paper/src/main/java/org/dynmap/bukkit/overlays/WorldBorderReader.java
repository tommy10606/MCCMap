// Modified for MCCMap: read-only compatibility adapter, independently implemented (2026).
package org.dynmap.bukkit.overlays;

import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;
import org.bukkit.plugin.Plugin;

/** Reads only the public WorldBorder getter contract; no third-party code or dependency is bundled. */
public final class WorldBorderReader {
    public record Border(double x, double z, double radiusX, double radiusZ, boolean round) {
        boolean valid() {
            return Double.isFinite(x) && Double.isFinite(z) && Double.isFinite(radiusX) && Double.isFinite(radiusZ)
                && radiusX > 0 && radiusZ > 0 && Double.isFinite(x - radiusX) && Double.isFinite(x + radiusX)
                && Double.isFinite(z - radiusZ) && Double.isFinite(z + radiusZ);
        }
    }
    private final Object api;
    private final Method border, x, z, radiusX, radiusZ, shape;
    private final BooleanSupplier defaultRound;

    WorldBorderReader(Object api, BooleanSupplier defaultRound) throws ReflectiveOperationException {
        this.api = api;
        this.defaultRound = defaultRound;
        border = api.getClass().getMethod("getWorldBorder", String.class);
        Class<?> data = border.getReturnType();
        x = data.getMethod("getX"); z = data.getMethod("getZ");
        radiusX = data.getMethod("getRadiusX"); radiusZ = data.getMethod("getRadiusZ"); shape = data.getMethod("getShape");
    }
    static WorldBorderReader bind(Plugin plugin) throws ReflectiveOperationException {
        Class<?> config = Class.forName("com.wimbli.WorldBorder.Config", false, plugin.getClass().getClassLoader());
        Method globalShape = config.getMethod("ShapeRound");
        return new WorldBorderReader(plugin, () -> {
            try { return (Boolean) globalShape.invoke(null); }
            catch (ReflectiveOperationException | ClassCastException failure) { throw new IllegalStateException("WorldBorder public shape API unavailable", failure); }
        });
    }
    public Border read(String world) throws ReflectiveOperationException {
        Object data = border.invoke(api, world);
        if (data == null) return null;
        Object configuredShape = shape.invoke(data);
        boolean round = configuredShape == null ? defaultRound.getAsBoolean() : (Boolean) configuredShape;
        Border result = new Border(((Number) x.invoke(data)).doubleValue(), ((Number) z.invoke(data)).doubleValue(),
            ((Number) radiusX.invoke(data)).doubleValue(), ((Number) radiusZ.invoke(data)).doubleValue(), round);
        return result.valid() ? result : null;
    }
}
