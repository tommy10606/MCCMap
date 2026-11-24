package org.dynmap.common.chunk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.dynmap.*;
import org.dynmap.common.BiomeMap;
import org.dynmap.renderer.DynmapBlockState;
import org.junit.jupiter.api.Test;

class PackedChunkPaletteTest {
    private long[] pack(int bits,int count,int paletteSize) {
        int perLong=64/bits;long[] data=new long[(count+perLong-1)/perLong];
        for(int i=0;i<count;i++)data[i/perLong]|=(long)(i%paletteSize)<<((i%perLong)*bits);
        return data;
    }
    private GenericChunk parse(GenericNBTCompound section) {
        var world=mock(DynmapWorld.class);world.minY=-64;world.worldheight=320;
        var cache=new GenericMapChunkCache(null){protected GenericChunk getLoadedChunk(DynmapChunk c){return null;}protected GenericChunk loadChunk(DynmapChunk c){return null;}};
        cache.setChunks(world,List.of());var root=mock(GenericNBTCompound.class);var sections=mock(GenericNBTList.class);
        when(root.getString("Status")).thenReturn("minecraft:full");when(root.contains("sections")).thenReturn(true);
        when(root.getList("sections",10)).thenReturn(sections);when(sections.size()).thenReturn(1);when(sections.getCompound(0)).thenReturn(section);
        when(section.getByte("Y")).thenReturn((byte)4);
        when(root.makeBitStorage(anyInt(),anyInt(),any(long[].class))).thenAnswer(call->{
            int bits=call.getArgument(0);long[] data=call.getArgument(2);int perLong=64/bits;
            return (GenericBitStorage)index->(int)((data[index/perLong]>>>((index%perLong)*bits))&((1L<<bits)-1));
        });
        return cache.parseChunkFromNBT(root);
    }
    @Test void elevenBitBlockPaletteIsNotMistakenForTwelveBitsBecauseOfLongPadding() {
        int size=1025;var palette=mock(GenericNBTList.class);var section=mock(GenericNBTCompound.class);var blocks=mock(GenericNBTCompound.class);
        when(section.contains("block_states",10)).thenReturn(true);when(section.getCompound("block_states")).thenReturn(blocks);
        when(blocks.contains("palette",9)).thenReturn(true);when(blocks.getList("palette",10)).thenReturn(palette);when(palette.size()).thenReturn(size);
        var expected=new ArrayList<DynmapBlockState>();
        for(int i=0;i<size;i++) {String name="mcc:packed11_"+i;expected.add(new DynmapBlockState.Builder().setBlockName(name).setStateName("").build());when(palette.getString(i)).thenReturn(name);}
        when(blocks.contains("data",12)).thenReturn(true);when(blocks.getLongArray("data")).thenReturn(pack(11,4096,size));
        var chunk=parse(section);
        for(int i=0;i<4096;i++)assertSame(expected.get(i%size),chunk.getBlockType(i&15,64+(i>>8),(i>>4)&15));
    }
    @Test void fiveBiomePaletteUsesThreeBitsEvenThoughItsPaddedDataHasFourLongs() {
        var names=List.of("minecraft:plains","minecraft:forest","minecraft:desert","minecraft:taiga","minecraft:ocean");
        var section=mock(GenericNBTCompound.class);var biomes=mock(GenericNBTCompound.class);var palette=mock(GenericNBTList.class);
        when(section.contains("biomes")).thenReturn(true);when(section.getCompound("biomes")).thenReturn(biomes);
        when(biomes.getList("palette",8)).thenReturn(palette);when(palette.size()).thenReturn(names.size());
        when(palette.getString(anyInt())).thenAnswer(call->names.get(call.getArgument(0)));
        when(biomes.getLongArray("data")).thenReturn(pack(3,64,names.size()));
        var chunk=parse(section);
        for(int i=0;i<64;i++)assertSame(BiomeMap.byBiomeResourceLocation(names.get(i%names.size())),chunk.getBiome((i&3)*4,64+(i>>4)*4,((i>>2)&3)*4));
    }
}
