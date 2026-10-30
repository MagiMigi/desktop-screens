package com.desktopscreens.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PanelMappingTest {
    /** The whole 1920x1080 frame of a monitor at -1024,176, drawn at half size from 100,50 of the window. */
    private final PanelMapping m = new PanelMapping(100, 50, 960, 540, 0, 0, 1920, 1080, -1024, 176);

    @Test
    void centerMapsToCenter() {
        assertArrayEquals(new int[] {-1024 + 960, 176 + 540}, m.toDesktop(580, 320));
    }

    @Test
    void topLeftCorner() {
        assertArrayEquals(new int[] {-1024, 176}, m.toDesktop(100, 50));
    }

    @Test
    void lastPixel() {
        assertArrayEquals(new int[] {-1024 + 1919, 176 + 1079}, m.toDesktop(1059.999, 589.999));
    }

    @Test
    void outsideIsNull() {
        assertNull(m.toDesktop(99.5, 300));
        assertNull(m.toDesktop(1060, 300));
        assertNull(m.toDesktop(500, 49.9));
        assertNull(m.toDesktop(500, 590));
    }

    @Test
    void atFractionFromTheTopLeft() {
        assertArrayEquals(new int[] {-1024, 176}, m.atFraction(0, 0));
        assertArrayEquals(new int[] {-64, 716}, m.atFraction(0.5, 0.5));
    }

    @Test
    void atFractionOutsideIsNull() {
        assertNull(m.atFraction(1, 0.5));
        assertNull(m.atFraction(0.5, -0.01));
    }

    @Test
    void desktopRectToWindow() {
        assertArrayEquals(new double[] {100, 50, 480, 270}, m.toWindowRect(-1024, 176, 960, 540), 1e-9);
    }

    @Test
    void desktopRectIsCutToThePicture() {
        assertArrayEquals(new double[] {100, 50, 62, 62}, m.toWindowRect(-1500, 0, 600, 300), 1e-9);
    }

    @Test
    void desktopRectOffThePictureIsNull() {
        assertNull(m.toWindowRect(2000, 0, 10, 10));
    }

    @Test
    void windowCrop() {
        PanelMapping window = new PanelMapping(0, 0, 800, 600, 200, 100, 800, 600, 0, 0);
        assertArrayEquals(new int[] {200, 100}, window.toDesktop(0, 0));
        assertArrayEquals(new int[] {999, 699}, window.toDesktop(799.9, 599.9));
    }
}
