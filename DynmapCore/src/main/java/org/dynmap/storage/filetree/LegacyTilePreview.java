package org.dynmap.storage.filetree;

import java.awt.image.BufferedImage;
import java.awt.RenderingHints;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.function.Function;
import javax.imageio.ImageIO;

/** Map raster edges in world-projection space, including the inverted Y origin. */
final class LegacyTilePreview {
    record Source(int x,int y,int zoom,int left,int top,int size) {}
    static List<Source> sources(int x,int y,int zoom,int shift) {
        if(shift < -4 || shift > 4 || zoom<0||zoom>30)throw new IllegalArgumentException("Unsupported legacy grid");
        int oldZoom=zoom==0 && shift<0 ? 0 : Math.max(0,zoom-shift);
        if(oldZoom>30)return List.of();
        double factor=Math.scalb(1.0,shift),step=1L<<oldZoom;
        double left=x/factor,top=((double)y+1)/factor,span=(1L<<zoom)/factor;
        double firstX=Math.floor(left/step)*step,firstY=Math.ceil((top-1)/step)*step;
        var result=new ArrayList<Source>(4);
        for(double tx=firstX;tx<left+span;tx+=step)for(double ty=firstY;ty+1>top-span;ty-=step) {
            int dx=(int)Math.round((tx-left)*128/span),dy=(int)Math.round((top-ty-1)*128/span),size=(int)Math.round(step*128/span);
            result.add(new Source((int)tx,(int)ty,oldZoom,dx,dy,size));
        }
        return result;
    }
    static BufferedImage render(int x,int y,int zoom,int shift,Function<Source,byte[]> reader) throws java.io.IOException {
        var image=new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB);var graphics=image.createGraphics();boolean found=false;
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            for(var source:sources(x,y,zoom,shift)) {
                byte[] data=reader.apply(source);if(data==null)continue;
                try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
                    var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())continue;
                    var decoder=readers.next();
                    try {
                        decoder.setInput(input,true,true);int width=decoder.getWidth(0),height=decoder.getHeight(0);
                        String format = decoder.getFormatName();
                        if((!format.equalsIgnoreCase("JPEG") && !format.equalsIgnoreCase("PNG"))||width!=height||width<128||width>1024)continue;
                        var original=decoder.read(0);
                        try {graphics.drawImage(original,source.left,source.top,source.left+source.size,source.top+source.size,0,0,width,height,null);found=true;}
                        finally{original.flush();}
                    }finally{decoder.dispose();}
                }
            }
        }finally{graphics.dispose();}
        if(found)return image;image.flush();return null;
    }
}
