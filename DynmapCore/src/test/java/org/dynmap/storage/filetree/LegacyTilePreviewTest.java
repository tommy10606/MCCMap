package org.dynmap.storage.filetree;
import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
class LegacyTilePreviewTest {
    private byte[] image(int color) {try{var im=new BufferedImage(128,128,BufferedImage.TYPE_INT_RGB);var g=im.createGraphics();g.setColor(new Color(color));g.fillRect(0,0,128,128);g.dispose();var bytes=new ByteArrayOutputStream();ImageIO.write(im,"jpg",bytes);return bytes.toByteArray();}catch(IOException e){throw new AssertionError(e);}}
    @Test void coarsePreviewCompositesAcrossTheInvertedYOriginInsteadOfShiftingTerrain() throws Exception {
        var im=LegacyTilePreview.render(0,0,2,2,s->image(s.y()==0?0xff0000:0x0000ff));
        assertTrue(((im.getRGB(64,10)>>16)&255)>240);assertTrue((im.getRGB(64,100)&255)>240);
        assertEquals(2,LegacyTilePreview.sources(0,0,2,2).size());
    }
    @Test void negativeNativeCoordinatesSelectTheCorrectOldTileAndRemainBounded() throws Exception {
        var sources=LegacyTilePreview.sources(-736,-545,0,2);assertEquals(1,sources.size());assertEquals(-184,sources.getFirst().x());assertEquals(-137,sources.getFirst().y());
        for(int shift=0;shift<=4;shift++)for(int z=0;z<20;z++)for(int x:new int[]{-1025,-1,0,35})assertTrue(LegacyTilePreview.sources(x,-x,z,shift).size()<=4);
        assertNotNull(LegacyTilePreview.render(-736,-545,0,2,s->image(0x00ff00)));
    }
    @Test void pngLegacyTilesAreAcceptedAndMissingOrUnsupportedImagesStayAbsent() throws Exception {
        assertNull(LegacyTilePreview.render(0,0,0,2,s->null));
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(128,128,BufferedImage.TYPE_INT_RGB),"png",bytes);
        assertNotNull(LegacyTilePreview.render(0,0,0,2,s->bytes.toByteArray()));
    }
    @Test void downscaledTerrainFeatureStaysAtItsWorldMarkerCoordinate() throws Exception {
        // World (-8999.5, 4980.5): source 16px/block -> (-1125,-623), pixel (8,72).
        // At 8px/block the same marker is tile (-563,-312), pixel (68,36).
        var feature=new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB);
        for(int y=70;y<74;y++)for(int x=6;x<10;x++)feature.setRGB(x,y,0xFFFF0000);
        var bytes=new ByteArrayOutputStream();ImageIO.write(feature,"png",bytes);
        var result=LegacyTilePreview.render(-563,-312,0,-1,source->source.x()==-1125 && source.y()==-623 ? bytes.toByteArray() : null);
        assertEquals(0xFFFF0000,result.getRGB(68,36));assertEquals(0,result.getRGB(8,72));
        assertEquals(4,LegacyTilePreview.sources(-563,-312,0,-1).size());
    }

    @Test void everyCoarseDownscaledLevelUsesTheSameWorldFootprintAsItsMarker() throws Exception {
        for(double[] location:new double[][]{{-8999.5,4980.5},{1234.5,-456.5},{-1.5,-1.5}})for(int zoom=0;zoom<=5;zoom++) {
            double wx=location[0],wz=location[1];int step=1<<zoom;
            int tx=(int)Math.floor(wx*8/(128*step))*step;
            int ty=(int)Math.ceil((-wz*8/128-1)/step)*step;
            var result=LegacyTilePreview.render(tx,ty,zoom,-1,source->{
                try {
                    var image=new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB);double sourceStep=1L<<source.zoom();
                    for(int y=0;y<128;y++)for(int x=0;x<128;x++) {
                        double blockX=(source.x()*128+(x+0.5)*sourceStep)/16;
                        double blockZ=-(128*(source.y()+1)-(y+0.5)*sourceStep)/16;
                        if(Math.abs(blockX-wx)<4*step && Math.abs(blockZ-wz)<4*step)image.setRGB(x,y,0xFFFF0000);
                    }
                    var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return bytes.toByteArray();
                }catch(IOException failure){throw new java.io.UncheckedIOException(failure);}
            });
            int px=(int)Math.floor((wx*8-tx*128)/step),py=(int)Math.floor((128*(ty+1)+wz*8)/step);
            assertEquals(0xFFFF0000,result.getRGB(px,py),"world feature at zoom "+zoom);
        }
    }

}
