package org.dynmap;
import static org.junit.jupiter.api.Assertions.*;
import org.dynmap.utils.Polygon;
import org.junit.jupiter.api.Test;
class BorderZoomTest {
    private Polygon border(double width){var p=new Polygon();p.addVertex(-width/2,-width/2);p.addVertex(width/2,-width/2);p.addVertex(width/2,width/2);p.addVertex(-width/2,width/2);return p;}
    @Test void largeBordersHaveCoarseEnoughNativeTilesWithoutGeneratingTerrain(){assertEquals(8,BorderZoom.extraLevels(border(36000),2));assertEquals(18,BorderZoom.extraLevels(border(59999968),2));}
    @Test void configuredExtraDetailAndAbsentBordersArePreserved(){assertEquals(12,BorderZoom.extraLevels(border(36000),12));assertEquals(2,BorderZoom.extraLevels(null,2));assertEquals(2,BorderZoom.extraLevels(border(16),2));}
}
