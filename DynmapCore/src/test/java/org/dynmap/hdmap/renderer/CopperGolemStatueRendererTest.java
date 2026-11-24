package org.dynmap.hdmap.renderer;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.util.BitSet;
import java.util.Map;
import org.dynmap.renderer.DynmapBlockState;
import org.dynmap.renderer.MapDataContext;
import org.dynmap.renderer.RenderPatch;
import org.dynmap.utils.PatchDefinition;
import org.dynmap.utils.PatchDefinitionFactory;
import org.junit.Test;

public class CopperGolemStatueRendererTest {
    @Test public void everyPoseAndFacingHasValidGeometryAndSkinRegions() {
        CopperGolemStatueRenderer renderer = new CopperGolemStatueRenderer();
        assertTrue(renderer.initializeRenderer(new PatchDefinitionFactory(), "minecraft:copper_golem_statue", new BitSet(), Map.of()));
        assertEquals(65, renderer.getMaximumTextureCount());
        for (String pose : new String[]{"standing", "sitting", "running", "star"}) {
            for (String facing : new String[]{"north", "east", "south", "west"}) {
                MapDataContext context = mock(MapDataContext.class);
                DynmapBlockState state = new DynmapBlockState.Builder().setBlockName("mcc:statue_" + pose + "_" + facing)
                        .setStateName("facing=" + facing + ",copper_golem_pose=" + pose + ",waterlogged=false").build();
                when(context.getBlockType()).thenReturn(state);
                RenderPatch[] patches = renderer.getRenderPatchList(context);
                assertEquals(pose.equals("sitting") ? 66 : 54, patches.length);
                double maxY = -1;
                for (RenderPatch patch : patches) {
                    assertNotNull(patch);
                    PatchDefinition p = (PatchDefinition) patch;
                    assertTrue(p.validate());
                    assertTrue(p.getTextureIndex() >= 0 && p.getTextureIndex() < renderer.getMaximumTextureCount());
                    assertTrue(p.umax > 0 && p.umax <= 1 && p.vmax > 0 && p.vmax <= 1);
                    for (double u : new double[]{0, p.umax}) for (double v : new double[]{0, p.vmax}) {
                        double x = p.x0 + p.u.x * u + p.v.x * v;
                        double y = p.y0 + p.u.y * u + p.v.y * v;
                        double z = p.z0 + p.u.z * u + p.v.z * v;
                        assertTrue(x >= -0.1 && x <= 1.1 && z >= -0.1 && z <= 1.1 && y >= -0.1 && y <= 1.6);
                        maxY = Math.max(maxY, y);
                    }
                }
                assertEquals(pose.equals("sitting") ? 1.24906 : pose.equals("running") ? 1.48031 : 1.49906, maxY, 0.00002);
                assertSame(patches, renderer.getRenderPatchList(context));
            }
        }
    }
}
