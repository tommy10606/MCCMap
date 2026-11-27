package org.dynmap.storage.filetree;

import java.util.*;

/** Native files and opaque overview regions needed before one old raster can disappear. */
final class LegacyCleanupPlan {
    record Check(int x, int y, int zoom, int left, int top, int right, int bottom) {}
    static List<Check> create(int x, int y, int zoom, int shift) {
        if(zoom<0||zoom>30||shift< -4||shift>4)return null;
        double scale=Math.scalb(1.0,shift),span=Math.scalb(1.0,zoom);
        double ax=Math.floor(x*scale),bx=Math.ceil((x+span)*scale)-1;
        double ay=Math.floor(((double)y+1-span)*scale),by=Math.ceil(((double)y+1)*scale)-1;
        if(ax<Integer.MIN_VALUE||ay<Integer.MIN_VALUE||bx>Integer.MAX_VALUE||by>Integer.MAX_VALUE||bx<ax||by<ay
                ||(bx-ax+1)*(by-ay+1)>4096)return null;
        long minX=(long)ax,maxX=(long)bx,minY=(long)ay,maxY=(long)by;
        var checks=new ArrayList<Check>();
        for(long ty=minY;ty<=maxY;ty++)for(long tx=minX;tx<=maxX;tx++)checks.add(new Check((int)tx,(int)ty,0,-1,-1,-1,-1));
        int first=zoom==0?1:Math.max(1,zoom+shift),last=zoom==0?Math.max(0,shift):zoom+shift;
        for(int q=first;q<=last;q++) {
            if(q>30)return null;
            long step=1L<<q,x0=Math.floorDiv(minX,step)*step,y0=-Math.floorDiv(-maxY,step)*step;
            for(long tx=x0;tx<=maxX;tx+=step)for(long ty=y0;ty>=minY;ty-=step) {
                if(tx<Integer.MIN_VALUE||tx>Integer.MAX_VALUE||ty<Integer.MIN_VALUE||ty>Integer.MAX_VALUE)return null;
                int left=(int)Math.max(0,Math.floor((minX-tx)*128.0/step));
                int right=(int)Math.min(128,Math.ceil((maxX+1-tx)*128.0/step));
                int top=(int)Math.max(0,Math.floor((ty-maxY)*128.0/step));
                int bottom=(int)Math.min(128,Math.ceil((ty-minY+1)*128.0/step));
                checks.add(new Check((int)tx,(int)ty,q,left,top,right,bottom));
            }
        }
        return checks;
    }
}
