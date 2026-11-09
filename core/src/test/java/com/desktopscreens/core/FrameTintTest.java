package com.desktopscreens.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FrameTintTest {
    private static final int GLASS = 0x0B0D11;

    @Test
    void framesOnTintsNothing() {
        for (int i = -1; i <= 4; i++) assertEquals(FrameTint.WHITE, FrameTint.color(true, i), "index " + i);
    }

    @Test
    void framesOffLeavesOtherIndexesWhite() {
        assertEquals(FrameTint.WHITE, FrameTint.color(false, 0)); // block-breaking and leaf particles
        assertEquals(FrameTint.WHITE, FrameTint.color(false, 4));
        assertEquals(FrameTint.WHITE, FrameTint.color(false, -1));
    }

    @Test
    void framesOffTintsTheThreeFrameColors() {
        assertEquals(FrameTint.TOP, FrameTint.color(false, 1));
        assertEquals(FrameTint.SIDES, FrameTint.color(false, 2));
        assertEquals(FrameTint.BOTTOM, FrameTint.color(false, 3));
    }

    @Test
    void eachTintTurnsItsFrameColorIntoTheGlass() {
        assertLandsOnGlass(0x2F3036, FrameTint.color(false, 1));
        assertLandsOnGlass(0x26272C, FrameTint.color(false, 2));
        assertLandsOnGlass(0x1C1D21, FrameTint.color(false, 3));
    }

    private static void assertLandsOnGlass(int frame, int tint) {
        for (int shift = 0; shift <= 16; shift += 8) {
            double tinted = (frame >> shift & 0xFF) * (tint >> shift & 0xFF) / 255.0;
            int glass = GLASS >> shift & 0xFF;
            assertTrue(Math.abs(tinted - glass) <= 0.5, String.format("frame %06X, channel at bit %d: %.2f, glass %d", frame, shift, tinted, glass));
        }
    }
}
