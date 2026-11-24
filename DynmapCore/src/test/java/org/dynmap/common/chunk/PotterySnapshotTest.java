package org.dynmap.common.chunk;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.dynmap.DynmapChunk;
import org.dynmap.DynmapWorld;
import org.junit.jupiter.api.Test;
class PotterySnapshotTest {
    private GenericChunk parse(GenericNBTCompound entity) {
        var world=mock(DynmapWorld.class);world.minY=-64;world.worldheight=320;
        var cache=new GenericMapChunkCache(null) {
            protected GenericChunk getLoadedChunk(DynmapChunk chunk){return null;}
            protected GenericChunk loadChunk(DynmapChunk chunk){return null;}
        };
        cache.setChunks(world,List.of());var root=mock(GenericNBTCompound.class);var entities=mock(GenericNBTList.class);
        when(root.getString("Status")).thenReturn("minecraft:full");when(root.contains("sections")).thenReturn(true);
        when(root.getList("sections",10)).thenReturn(mock(GenericNBTList.class));
        when(root.contains("block_entities",9)).thenReturn(true);when(root.getList("block_entities",10)).thenReturn(entities);
        when(entities.size()).thenReturn(1);when(entities.getCompound(0)).thenReturn(entity);
        when(entity.getString("id")).thenReturn("minecraft:decorated_pot");when(entity.getInt("y")).thenReturn(64);
        return cache.parseChunkFromNBT(root);
    }
    @Test void readsCurrentNamedSherdsWithoutRetainingItemContents() {
        var entity=mock(GenericNBTCompound.class);var sherds=mock(GenericNBTCompound.class);
        when(entity.contains("sherds",10)).thenReturn(true);when(entity.getCompound("sherds")).thenReturn(sherds);
        for(String side:List.of("back","left","right","front")) {
            var item=mock(GenericNBTCompound.class);when(sherds.getCompound(side)).thenReturn(item);
            when(item.getString("id")).thenReturn("minecraft:"+side+"_pottery_sherd");
        }
        assertEquals(List.of("minecraft:back_pottery_sherd","minecraft:left_pottery_sherd","minecraft:right_pottery_sherd","minecraft:front_pottery_sherd"),parse(entity).getPottery(0,64,0));
        verify(entity,never()).getCompound("item");
    }
    @Test void retainsLegacySherdOrderWhenReadingAnOlderChunk() {
        var entity=mock(GenericNBTCompound.class);var sherds=mock(GenericNBTList.class);
        when(entity.contains("sherds",9)).thenReturn(true);when(entity.getList("sherds",8)).thenReturn(sherds);when(sherds.size()).thenReturn(4);
        for(int i=0;i<4;i++)when(sherds.getString(i)).thenReturn("sherd"+i);
        assertEquals(List.of("sherd0","sherd1","sherd2","sherd3"),parse(entity).getPottery(0,64,0));
    }
    @Test void keepsOnlyCopiedVisualDataAndRetainsNegativeHeights() {
        var builder=new GenericChunk.Builder(-64,320);var sherds=new ArrayList<>(List.of("one","two","three","four"));
        var snapshot=builder.pottery(17,-63,-1,sherds).build();sherds.set(0,"changed");
        assertEquals("one",snapshot.getPottery(1,-63,15).getFirst());assertNull(snapshot.getPottery(1,193,15));
        builder.reset(-64,320);assertNull(builder.build().getPottery(1,-63,15));assertNotNull(snapshot.getPottery(1,-63,15));
    }
}
