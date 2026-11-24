package org.dynmap;
import org.dynmap.utils.Polygon;
/** Keep the widest stored tiles coarse enough to fit the finite world border safely. */
public final class BorderZoom {
    private BorderZoom() {}
    public static int extraLevels(Polygon border, int configured) {
        if(border==null||border.size()<3)return configured;
        double minX=Double.POSITIVE_INFINITY,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxZ=maxX;
        for(int i=0;i<border.size();i++){var p=border.getVertex(i);if(!Double.isFinite(p.x)||!Double.isFinite(p.y))return configured;minX=Math.min(minX,p.x);maxX=Math.max(maxX,p.x);minZ=Math.min(minZ,p.y);maxZ=Math.max(maxZ,p.y);}
        double span=Math.max(maxX-minX,maxZ-minZ);
        return span>0?Math.max(configured,Math.min(20,(int)Math.ceil(Math.log(span/256)/Math.log(2)))):configured;
    }
}
