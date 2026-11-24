package org.dynmap.bukkit.overlays;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.regions.*;
import org.junit.jupiter.api.Test;
class WorldGuardRegionOverlayTest {
    @Test void cuboidsIncludeTheMaximumBlockAndRetainHeightAndPriority() {
        var region=new ProtectedCuboidRegion("spawn",BlockVector3.at(-10,-64,20),BlockVector3.at(10,150,30));region.setPriority(7);
        var shape=WorldGuardRegionOverlay.shape("world",region);
        assertEquals("spawn",shape.label());assertEquals(4,shape.points().size());
        assertEquals(11,shape.points().get(2).x());assertEquals(31,shape.points().get(2).z());
        assertTrue(WorldGuardRegionOverlay.description(shape).contains("X: -10 to 10"));
        assertTrue(WorldGuardRegionOverlay.description(shape).contains("Z: 20 to 30"));
        assertEquals(-64,shape.minY());assertEquals(150,shape.maxY());assertEquals(7,shape.priority());
    }
    @Test void polygonsRetainTheirActualVertices() {
        var region=new ProtectedPolygonalRegion("triangle",List.of(BlockVector2.at(0,0),BlockVector2.at(10,0),BlockVector2.at(5,10)),-64,319);
        var shape=WorldGuardRegionOverlay.shape("world",region);
        assertEquals(3,shape.points().size());assertEquals(5,shape.points().get(2).x());assertEquals(10,shape.points().get(2).z());
    }
    @Test void globalRegionsHaveNoFiniteOutline() { assertNull(WorldGuardRegionOverlay.shape("world",new GlobalProtectedRegion("__global__"))); }
    @Test void ownershipChangesRefreshThePopupAndUncachedUsersKeepTheirUuid() {
        var region=new ProtectedCuboidRegion("spawn",BlockVector3.ZERO,BlockVector3.at(10,100,10));
        var id=java.util.UUID.randomUUID();region.getOwners().addPlayer(id);region.getMembers().addPlayer("<visitor>");region.getMembers().addGroup("builders");
        var before=WorldGuardRegionOverlay.shape("world",region,uuid->"Tommy10606");
        assertEquals(List.of("Tommy10606"),before.owners());
        String html=WorldGuardRegionOverlay.description(before);
        assertTrue(html.contains("Owners: Tommy10606"));assertTrue(html.contains("group: builders"));assertFalse(html.contains("<visitor>"));
        region.getOwners().addPlayer("second");assertNotEquals(before,WorldGuardRegionOverlay.shape("world",region));
        assertTrue(WorldGuardRegionOverlay.shape("world",region).owners().contains(id.toString()));
    }
}
