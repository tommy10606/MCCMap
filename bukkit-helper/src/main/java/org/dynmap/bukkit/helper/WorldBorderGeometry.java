// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap.bukkit.helper;
import org.bukkit.WorldBorder;
import org.dynmap.utils.Polygon;

public final class WorldBorderGeometry {
    public static Polygon from(WorldBorder border) {
        if (border == null) return null;
        double half = border.getSize() / 2.0;
        var center = border.getCenter();
        double x = center.getX(), z = center.getZ(), limit = border.getMaxCenterCoordinate();
        if (!Double.isFinite(half) || half <= 0 || !Double.isFinite(x) || !Double.isFinite(z)
            || !Double.isFinite(limit) || limit <= 0) return null;
        Polygon shape = new Polygon();
        double minX = Math.max(x - half, -limit), maxX = Math.min(x + half, limit);
        double minZ = Math.max(z - half, -limit), maxZ = Math.min(z + half, limit);
        if (minX >= maxX || minZ >= maxZ) return null;
        shape.addVertex(minX, minZ);
        shape.addVertex(maxX, minZ);
        shape.addVertex(maxX, maxZ);
        shape.addVertex(minX, maxZ);
        return shape;
    }
    private WorldBorderGeometry() {}
}
