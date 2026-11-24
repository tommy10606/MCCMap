package org.dynmap.hdmap.renderer;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.dynmap.Log;
import org.dynmap.renderer.CustomRenderer;
import org.dynmap.renderer.MapDataContext;
import org.dynmap.renderer.RenderPatch;
import org.dynmap.renderer.RenderPatchFactory;
import org.json.simple.parser.JSONParser;

/** Baked statue poses, with entity-skin regions kept available to resource packs. */
public class CopperGolemStatueRenderer extends CustomRenderer {
    private final Map<String, RenderPatch[][]> poses = new HashMap<>();
    private int textureCount;

    @Override
    public boolean initializeRenderer(RenderPatchFactory factory, String name, BitSet mask, Map<String, String> parameters) {
        if (!super.initializeRenderer(factory, name, mask, parameters)) return false;
        try (var stream = getClass().getResourceAsStream("/models/copper-golem-statues.json")) {
            if (stream == null) throw new IllegalStateException("Missing statue geometry");
            Map<?, ?> source = (Map<?, ?>) new JSONParser().parse(new InputStreamReader(stream, StandardCharsets.UTF_8));
            for (var entry : source.entrySet()) {
                List<?> faces = (List<?>) entry.getValue();
                RenderPatch[][] rotations = new RenderPatch[4][faces.size()];
                for (int face = 0; face < faces.size(); face++) {
                    List<?> values = (List<?>) faces.get(face);
                    double[] p = values.stream().mapToDouble(v -> ((Number) v).doubleValue()).toArray();
                    int texture = (int) p[11];
                    textureCount = Math.max(textureCount, texture + 1);
                    RenderPatch patch = factory.getPatch(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8],
                            0, p[9], 0, p[10], RenderPatchFactory.SideVisible.BOTH, texture);
                    if (patch == null) throw new IllegalStateException("Invalid statue face " + face);
                    rotations[0][face] = patch;
                    for (int rotation = 1; rotation < 4; rotation++) {
                        rotations[rotation][face] = factory.getRotatedPatch(patch, 0, rotation * 90, 0, texture);
                        if (rotations[rotation][face] == null) throw new IllegalStateException("Invalid statue rotation");
                    }
                }
                poses.put((String) entry.getKey(), rotations);
            }
            return poses.keySet().containsAll(List.of("standing", "sitting", "running", "star"));
        } catch (Exception exception) {
            Log.severe("Unable to load copper statue geometry", exception);
            return false;
        }
    }

    @Override public RenderPatch[] getRenderPatchList(MapDataContext context) {
        String pose = "standing";
        int rotation = 0;
        for (String property : context.getBlockType().stateList) {
            if (property.startsWith("copper_golem_pose=")) pose = property.substring("copper_golem_pose=".length());
            if (property.startsWith("facing=")) rotation = switch (property.substring(7)) {
                case "east" -> 1;
                case "south" -> 2;
                case "west" -> 3;
                default -> 0;
            };
        }
        return poses.getOrDefault(pose, poses.get("standing"))[rotation];
    }

    @Override public int getMaximumTextureCount() { return textureCount; }
    @Override public boolean isOnlyBlockStateSensitive() { return true; }
}
