package org.dynmap;

import static org.junit.Assert.*;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import org.junit.Test;

public class PlayerSkinCompositionTest {
    private int[] compose(int base, int... overlays) {
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < 64; i++) { skin.setRGB(8+i%8, 8+i/8, base); skin.setRGB(40+i%8, 8+i/8, overlays[i%overlays.length]); }
        int[] target = new int[64];
        PlayerFaces.copyLayersToTarget(skin, 8, 8, 40, 8, 8, 8, target, 0, 8);
        return target;
    }
    @Test public void translucentUniformOverlayBlendsWithOpaqueFace() {
        for (int pixel : compose(0xFFFF0000, 0x800000FF)) assertEquals(0xFF7F0080, pixel);
    }
    @Test public void transparentAndOpaqueAccessoryPixelsKeepTheirExpectedBehavior() {
        int[] result = compose(0xFF336699, 0x0000FF00, 0xFF123456);
        assertEquals(0xFF336699, result[0]); assertEquals(0xFF123456, result[1]);
    }
    @Test public void translucentBaseUsesStraightAlphaRatherThanDarkeningColors() {
        assertEquals(0xC05500AA, compose(0x80FF0000, 0x800000FF)[0]);
        assertEquals(0x800000FF, compose(0x00000000, 0x800000FF)[0]);
    }
    @Test public void legacySolidOpaqueHatRegionDoesNotCoverTheFace() {
        assertTrue(Arrays.stream(compose(0xFF336699, 0xFF000000)).allMatch(p -> p == 0xFF336699));
    }
    @Test public void legacyShortSkinAndStridedBodyDestinationAreSupported() {
        BufferedImage skin = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        skin.setRGB(20, 20, 0xFFABCDEF);
        int[] target = new int[32*32]; Arrays.fill(target, 0x12345678);
        PlayerFaces.copyLayersToTarget(skin, 20, 20, 20, 36, 8, 12, target, 8*32+12, 32);
        assertEquals(0xFFABCDEF, target[8*32+12]); assertEquals(0x12345678, target[8*32+11]);
        assertEquals(0x12345678, target[20*32+12]);
    }
}
