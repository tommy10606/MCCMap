package org.dynmap.bukkit;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import org.bukkit.WorldBorder;
import org.bukkit.Location;
import org.dynmap.bukkit.helper.WorldBorderGeometry;
import org.junit.jupiter.api.Test;
class WorldBorderGeometryTest {
    private WorldBorder border(double x,double z,double size) {
        return (WorldBorder)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WorldBorder.class},(p,m,a)->switch(m.getName()){
            case "getSize" -> size;case "getCenter" -> new Location(null,x,64,z);case "getMaxCenterCoordinate" -> 29999984.0;default -> null;});
    }
    @Test void showsSmallAndVeryLargeBorders() {
        var tiny=WorldBorderGeometry.from(border(10,20,1));assertEquals(9.5,tiny.getVertex(0).x);assertEquals(20.5,tiny.getVertex(2).y);
        var large=WorldBorderGeometry.from(border(0,0,12000000));assertNotNull(large);assertEquals(6000000.0,large.getVertex(2).x);
    }
    @Test void comparesCoordinatesAndDetectsMovedOrResizedBorders() {
        var original=WorldBorderGeometry.from(border(0,0,100));var copy=WorldBorderGeometry.from(border(0,0,100));
        assertEquals(original,copy);assertEquals(original.hashCode(),copy.hashCode());
        assertNotEquals(original,WorldBorderGeometry.from(border(1,0,100)));
        assertNotEquals(original,WorldBorderGeometry.from(border(0,0,50)));
        assertNotEquals(original,null);
    }
    @Test void clampsAtVanillaLimitsAndRejectsInvalidGeometry() {
        var clamped=WorldBorderGeometry.from(border(29999980,0,100));assertEquals(29999984.0,clamped.getVertex(2).x);
        assertNull(WorldBorderGeometry.from(border(0,0,Double.NaN)));
        assertNull(WorldBorderGeometry.from(null));
    }
}
