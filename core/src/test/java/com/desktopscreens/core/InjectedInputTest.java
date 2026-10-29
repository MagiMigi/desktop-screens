package com.desktopscreens.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InjectedInputTest {
    /** Windows turns 0-65535 back into pixels as floor(value * size / 65536) from the virtual desktop's corner. */
    private static int pixel(int normalized, int origin, int size) {
        return origin + (int) Math.floor(normalized * (double) size / 65536);
    }

    @Test
    void everyPixelOfTheUsersDesktopComesBack() {
        // Two monitors side by side: 1024 wide at -1024, then 1920 from 0.
        int origin = -1024, size = 1024 + 1920;
        for (int x = origin; x < origin + size; x++) {
            int n = InjectedInput.normalized(x, origin, size);
            assertTrue(n >= 0 && n <= 65535, "in range for " + x);
            assertEquals(x, pixel(n, origin, size), "pixel " + x);
        }
    }

    @Test
    void edgesOfOneMonitor() {
        assertEquals(0, InjectedInput.normalized(0, 0, 1920));
        assertEquals(1079, pixel(InjectedInput.normalized(1079, 0, 1080), 0, 1080));
    }
}
