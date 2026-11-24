package org.dynmap.common.chunk;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.dynmap.*;
import org.dynmap.common.BiomeMap;
import org.junit.jupiter.api.Test;
class BiomeBlendTest {
 @Test void grassUsesFiveByFiveSamplesAtTheCurrentHeight() {
  List<String> samples=new ArrayList<>();
  var cache=new GenericMapChunkCache(null){
   protected GenericChunk getLoadedChunk(DynmapChunk chunk){return null;} protected GenericChunk loadChunk(DynmapChunk chunk){return null;}
   @Override protected BiomeMap getBlockBiome(int x,int y,int z){samples.add(x+","+y+","+z);return BiomeMap.PLAINS;}
   @Override public int getGrassColor(BiomeMap biome,int[] colors,int x,int z){return x==6||x==10?0xFF0000:0;}
  };
  var world=mock(DynmapWorld.class);world.minY=-64;world.worldheight=320;when(world.getEnvironment()).thenReturn("normal");cache.setChunks(world,List.of());
  assertEquals(0x660000,cache.getIterator(8,64,8).getSmoothGrassColorMultiplier(new int[65536]));
  assertEquals(25,new HashSet<>(samples).size());assertTrue(samples.contains("6,64,6"));assertTrue(samples.contains("10,64,10"));
 }
}
