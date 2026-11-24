package org.dynmap.hdmap.renderer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.dynmap.renderer.*;
import org.dynmap.utils.PatchDefinition;
import org.dynmap.utils.PatchDefinitionFactory;
import org.junit.jupiter.api.Test;

class DecoratedPotRendererTest {
    @Test void allFacingsAndSherdPatternsUseValidMeshesAndIndependentSideTextures() {
        var renderer=new DecoratedPotRenderer();assertTrue(renderer.initializeRenderer(new PatchDefinitionFactory(),"minecraft:decorated_pot",new BitSet(),Map.of()));
        for(String facing:List.of("north","east","south","west")) {
            var context=mock(MapDataContext.class);
            when(context.getBlockType()).thenReturn(new DynmapBlockState.Builder().setBlockName("mcc:pot_"+facing).setStateName("facing="+facing).build());
            when(context.getBlockTileEntityField("sherds")).thenReturn(List.of("minecraft:angler_pottery_sherd","minecraft:archer_pottery_sherd","minecraft:brick","minecraft:heart_pottery_sherd"));
            var mesh=renderer.getRenderPatchList(context);assertEquals(20,mesh.length);
            var ids=new HashSet<Integer>();
            for(var face:mesh){var patch=(PatchDefinition)face;assertTrue(patch.validate());assertTrue(patch.getTextureIndex()<renderer.getMaximumTextureCount());ids.add(patch.getTextureIndex());}
            assertTrue(ids.size()>14);
        }
    }
}
