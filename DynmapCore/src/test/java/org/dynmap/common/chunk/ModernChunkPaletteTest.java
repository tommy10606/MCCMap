// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap.common.chunk;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.dynmap.DynmapChunk;
import org.dynmap.DynmapWorld;
import org.dynmap.renderer.DynmapBlockState;
import org.junit.Test;

/** Regression coverage for current Minecraft's string block-state palettes. */
public class ModernChunkPaletteTest {
    private GenericChunk parse(String state) {
        GenericNBTList palette = mock(GenericNBTList.class);
        when(palette.size()).thenReturn(1);
        when(palette.getString(0)).thenReturn(state);
        return parse(palette);
    }
    private GenericChunk parse(GenericNBTList palette) {
        if (DynmapBlockState.getBaseStateByName("minecraft:stone") == DynmapBlockState.AIR)
            new DynmapBlockState.Builder().setBlockName("minecraft:stone").setStateName("").setSolid().build();
        if (DynmapBlockState.getBaseStateByName("minecraft:water") == DynmapBlockState.AIR)
            new DynmapBlockState.Builder().setBlockName("minecraft:water").setStateName("level=0").build();
        DynmapWorld world = mock(DynmapWorld.class);
        world.minY = -64;
        world.worldheight = 320;
        GenericMapChunkCache cache = new GenericMapChunkCache(null) {
            protected GenericChunk getLoadedChunk(DynmapChunk chunk) { return null; }
            protected GenericChunk loadChunk(DynmapChunk chunk) { return null; }
        };
        cache.setChunks(world, List.of());
        GenericNBTCompound root = mock(GenericNBTCompound.class);
        GenericNBTCompound section = mock(GenericNBTCompound.class);
        GenericNBTCompound blocks = mock(GenericNBTCompound.class);
        GenericNBTList sections = mock(GenericNBTList.class);
        when(root.getString("Status")).thenReturn("minecraft:full");
        when(root.contains("sections")).thenReturn(true);
        when(root.getList("sections", 10)).thenReturn(sections);
        when(root.makeBitStorage(eq(1), eq(4096), any(long[].class))).thenReturn(index -> 0);
        when(sections.size()).thenReturn(1);
        when(sections.getCompound(0)).thenReturn(section);
        when(section.getByte("Y")).thenReturn((byte) 4);
        when(section.contains("block_states", 10)).thenReturn(true);
        when(section.getCompound("block_states")).thenReturn(blocks);
        when(blocks.contains("palette", 9)).thenReturn(true);
        when(blocks.getList("palette", 10)).thenReturn(palette);
        return cache.parseChunkFromNBT(root);
    }
    @Test public void singletonStringPaletteProducesNonEmptyChunk() {
        GenericChunk chunk = parse("minecraft:stone");
        assertFalse(chunk.isEmpty);
        assertEquals("minecraft:stone", chunk.getBlockType(0, 64, 0).blockName);
        assertTrue(chunk.getBlockType(0, 63, 0).isAir());
    }
    @Test public void stringPalettePreservesStateProperties() {
        DynmapBlockState expected = new DynmapBlockState.Builder().setBlockName("mcc:palette_test")
            .setStateName("facing=north,waterlogged=false").setSolid().build();
        assertSame(expected, parse("mcc:palette_test[facing=north,waterlogged=false]").getBlockType(7, 70, 9));
    }
    @Test public void omittedPropertiesResolveToTheNativeDefaultInsteadOfAirOrTheFirstState() {
        String name="mcc:grass_default_palette";
        DynmapBlockState first=new DynmapBlockState.Builder().setBlockName(name).setStateName("snowy=true").build();
        DynmapBlockState normal=new DynmapBlockState.Builder().setBlockName(name).setBaseState(first).setStateIndex(1).setStateName("snowy=false").build();
        normal.setAsDefaultState();
        assertSame(normal,parse(name).getBlockType(0,64,0));
        assertSame(first,parse(name+"[snowy=true]").getBlockType(0,64,0));
        assertSame(normal,parse(name+"[snowy=false]").getBlockType(0,64,0));
    }
    @Test public void partialPropertiesAreCompletedFromDefaultsRatherThanArbitraryRegistryOrder() {
        String name="mcc:partial_palette";DynmapBlockState first=null,wet=null,normal=null;int index=0;
        for(String snowy:List.of("true","false"))for(String water:List.of("true","false")) {
            var state=new DynmapBlockState.Builder().setBaseState(first).setStateIndex(index++).setBlockName(name)
                .setStateName("snowy="+snowy+",waterlogged="+water).build();
            if(first==null)first=state;if(snowy.equals("false")&&water.equals("true"))wet=state;
            if(snowy.equals("false")&&water.equals("false")){normal=state;state.setAsDefaultState();}
        }
        assertSame(wet,parse(name+"[waterlogged=true]").getBlockType(0,64,0));
        assertSame(normal,parse(name).getBlockType(0,64,0));
    }
    @Test public void compoundPalettesWithoutPropertiesAlsoUseDefaults() {
        String name="mcc:compound_default_palette";
        var first=new DynmapBlockState.Builder().setBlockName(name).setStateName("snowy=true").build();
        var normal=new DynmapBlockState.Builder().setBlockName(name).setBaseState(first).setStateIndex(1).setStateName("snowy=false").build();normal.setAsDefaultState();
        var palette=mock(GenericNBTList.class);var entry=mock(GenericNBTCompound.class);when(palette.size()).thenReturn(1);
        when(palette.getCompound(0)).thenReturn(entry);when(entry.getString("Name")).thenReturn(name);
        assertSame(normal,parse(palette).getBlockType(0,64,0));
    }
    @Test public void currentLowercaseCompoundCodecPreservesNonDefaultProperties() {
        String name="mcc:lowercase_palette";
        var normal=new DynmapBlockState.Builder().setBlockName(name).setStateName("snowy=false").build();normal.setAsDefaultState();
        var snowy=new DynmapBlockState.Builder().setBlockName(name).setBaseState(normal).setStateIndex(1).setStateName("snowy=true").build();
        var palette=mock(GenericNBTList.class);var entry=mock(GenericNBTCompound.class);var properties=mock(GenericNBTCompound.class);
        when(palette.size()).thenReturn(1);when(palette.getCompound(0)).thenReturn(entry);
        when(entry.contains("id")).thenReturn(true);when(entry.getString("id")).thenReturn(name);
        when(entry.contains("properties")).thenReturn(true);when(entry.getCompound("properties")).thenReturn(properties);
        when(properties.getAllKeys()).thenReturn(java.util.Set.of("snowy"));when(properties.getAsString("snowy")).thenReturn("true");
        assertSame(snowy,parse(palette).getBlockType(0,64,0));
    }
}
