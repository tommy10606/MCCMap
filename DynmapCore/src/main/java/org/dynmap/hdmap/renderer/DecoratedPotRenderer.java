package org.dynmap.hdmap.renderer;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.dynmap.Log;
import org.dynmap.renderer.*;
import org.json.simple.parser.JSONParser;

/** Current pottery geometry and the four independently selected sherd faces. */
public final class DecoratedPotRenderer extends CustomRenderer {
    private RenderPatch[][] base;
    private final Map<String, RenderPatch[][][]> sides = new LinkedHashMap<>();
    private List<String> patterns;
    private int textureCount;
    @Override public boolean initializeRenderer(RenderPatchFactory factory, String name, BitSet mask, Map<String,String> parameters) {
        if (!super.initializeRenderer(factory,name,mask,parameters)) return false;
        try (var stream=getClass().getResourceAsStream("/models/decorated-pot.json")) {
            var source=(Map<?,?>)new JSONParser().parse(new InputStreamReader(stream,StandardCharsets.UTF_8));
            patterns=((List<?>)source.get("patterns")).stream().map(Object::toString).toList();
            int baseTextures=((Number)source.get("baseTextureCount")).intValue();
            textureCount=baseTextures+patterns.size();
            base=mesh(factory,(List<?>)source.get("base"),-1);
            for(String side:List.of("back","left","right","front")) {
                var options=new RenderPatch[patterns.size()][][];
                for(int i=0;i<patterns.size();i++) options[i]=mesh(factory,(List<?>)source.get(side),baseTextures+i);
                sides.put(side,options);
            }
            return true;
        } catch(Exception failure) { Log.severe("Could not load decorated pot geometry",failure);return false; }
    }
    private RenderPatch[][] mesh(RenderPatchFactory factory,List<?> source,int override) {
        var rotations=new RenderPatch[4][source.size()];
        for(int face=0;face<source.size();face++) {
            double[] p=((List<?>)source.get(face)).stream().mapToDouble(n->((Number)n).doubleValue()).toArray();
            int texture=override<0?(int)p[11]:override;
            var patch=factory.getPatch(p[0],p[1],p[2],p[3],p[4],p[5],p[6],p[7],p[8],0,p[9],0,p[10],RenderPatchFactory.SideVisible.BOTH,texture);
            if(patch==null) throw new IllegalStateException("Invalid pottery mesh");
            rotations[0][face]=patch;
            for(int i=1;i<4;i++) rotations[i][face]=factory.getRotatedPatch(patch,0,i*90,0,texture);
        }
        return rotations;
    }
    @Override public RenderPatch[] getRenderPatchList(MapDataContext context) {
        int rotation=0;
        for(String state:context.getBlockType().stateList) if(state.startsWith("facing=")) rotation=switch(state.substring(7)) {
            case "east"->1;case "south"->2;case "west"->3;default->0;
        };
        var result=new ArrayList<RenderPatch>(Arrays.asList(base[rotation]));
        Object data=context.getBlockTileEntityField("sherds");List<?> sherds=data instanceof List<?> list?list:List.of();int index=0;
        for(var meshes:sides.values()) {
            String item=index<sherds.size()?String.valueOf(sherds.get(index)):"";index++;
            String pattern=item.replace("minecraft:","").replace("_pottery_sherd","_pottery_pattern");
            int selected=patterns.indexOf(pattern);if(selected<0)selected=0;
            Collections.addAll(result,meshes[selected][rotation]);
        }
        return result.toArray(RenderPatch[]::new);
    }
    @Override public int getMaximumTextureCount(){return textureCount;}
    @Override public String[] getTileEntityFieldsNeeded(){return new String[]{"sherds"};}
}
