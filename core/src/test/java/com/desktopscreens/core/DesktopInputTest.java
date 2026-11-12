package com.desktopscreens.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DesktopInputTest {
    /** The test PC: the main monitor 1920x1080 at 0,0, a 1024x768 one to its left at -1024,176. */
    private static final int[] SCREEN = {-1024, 0, 2944, 1080};

    private static boolean confines(int left, int top, int right, int bottom) {
        return DesktopInput.confines(left, top, right, bottom, SCREEN[0], SCREEN[1], SCREEN[2], SCREEN[3]);
    }

    @Test
    void aFreeMouseIsNotConfined() {
        // With no clip, Windows reports the whole virtual screen.
        assertFalse(confines(-1024, 0, 1920, 1080));
    }

    @Test
    void aFullscreenGameKeepingTheMouseInItsWindowConfinesIt() {
        // LWJGL 2 (1.12.2) in fullscreen, or SDL (26.3) in exclusive fullscreen, on the main monitor.
        assertTrue(confines(0, 0, 1920, 1080));
    }

    @Test
    void anyEdgeShortOfTheScreenConfines() {
        assertTrue(confines(-1023, 0, 1920, 1080));
        assertTrue(confines(-1024, 1, 1920, 1080));
        assertTrue(confines(-1024, 0, 1919, 1080));
        assertTrue(confines(-1024, 0, 1920, 1079));
    }

    @Test
    void aRectangleBeyondTheScreenDoesNotConfine() {
        assertFalse(confines(-2000, -100, 3000, 2000));
    }
}
