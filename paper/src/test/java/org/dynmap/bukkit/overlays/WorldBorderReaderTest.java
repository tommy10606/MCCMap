package org.dynmap.bukkit.overlays;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class WorldBorderReaderTest {
    // Independently written fixtures model public getter signatures only; no WorldBorder implementation is used.
    public static class DataFixture {
        double x=40,z=50; int rx=20,rz=10; Boolean round=false;
        public double getX(){return x;} public double getZ(){return z;}
        public int getRadiusX(){return rx;} public int getRadiusZ(){return rz;}
        public Boolean getShape(){return round;}
    }
    public static class ApiFixture {
        DataFixture data=new DataFixture();
        public DataFixture getWorldBorder(String world){return world.equals("world")?data:null;}
    }
    @Test void readsCustomCenterAndBothRadiiWithoutModifyingTheProvider() throws Exception {
        var api=new ApiFixture();var b=new WorldBorderReader(api,()->true).read("world");
        assertEquals(new WorldBorderReader.Border(40,50,20,10,false),b);
        assertEquals(40,api.data.x);assertEquals(20,api.data.rx);
    }
    @Test void handlesCircularEllipticalAndInheritedShapes() throws Exception {
        var api=new ApiFixture();api.data.round=true;
        assertTrue(new WorldBorderReader(api,()->false).read("world").round());
        api.data.round=null;
        assertTrue(new WorldBorderReader(api,()->true).read("world").round());
        assertFalse(new WorldBorderReader(api,()->false).read("world").round());
    }
    @Test void missingAndInvalidBordersProduceNoOutline() throws Exception {
        var api=new ApiFixture();var reader=new WorldBorderReader(api,()->false);
        assertNull(reader.read("missing"));api.data.rx=0;assertNull(reader.read("world"));
        api.data.rx=20;api.data.x=Double.NaN;assertNull(reader.read("world"));
    }
    @Test void editsAndDeletionAreReadFromLiveApiState() throws Exception {
        var api=new ApiFixture();var reader=new WorldBorderReader(api,()->false);var before=reader.read("world");
        api.data.rx=100;assertNotEquals(before,reader.read("world"));
        api.data=null;assertNull(reader.read("world"));
    }
    @Test void incompatibleApisFailClosed() {assertThrows(ReflectiveOperationException.class,()->new WorldBorderReader(new Object(),()->false));}
}
