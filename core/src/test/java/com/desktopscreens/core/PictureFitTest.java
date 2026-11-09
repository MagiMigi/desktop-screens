package com.desktopscreens.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PictureFitTest {
    private static final float DELTA = 1e-4f;

    @Test
    void sixteenByNineFillsSevenByFour() {
        assertTrue(PictureFit.fills(7, 4, 1920, 1080));
    }

    @Test
    void sixteenByNineIsTooFarFromFiveByThree() {
        assertFalse(PictureFit.fills(5, 3, 1920, 1080));
    }

    @Test
    void sixteenByNineFillsElevenBySixWithoutFrames() {
        assertTrue(PictureFit.fills(11, 6, 1920, 1080));
    }

    @Test
    void sixteenByNineIsTooFarFromElevenBySixWithFrames() {
        assertFalse(PictureFit.fills(10.875, 5.875, 1920, 1080));
    }

    @Test
    void fourByThreeFillsAFramedFourByThree() {
        assertTrue(PictureFit.fills(3.875, 2.875, 1024, 768));
    }

    @Test
    void theLimitItselfStillFills() {
        assertTrue(PictureFit.fills(1.035, 1, 1, 1));
        assertTrue(PictureFit.fills(1, 1.035, 1, 1));
    }

    @Test
    void justPastTheLimitDoesNot() {
        assertFalse(PictureFit.fills(1.036, 1, 1, 1));
    }

    @Test
    void stretchedFillsTheWholeArea() {
        assertArrayEquals(new float[] {0, 0, 7, 4}, PictureFit.place(7, 4, 1920, 1080, 0), DELTA);
    }

    @Test
    void straightenedHasItsTrueShape() {
        assertArrayEquals(new float[] {0, 0.03125f, 7, 3.9375f}, PictureFit.place(7, 4, 1920, 1080, 1), DELTA);
    }

    @Test
    void halfwayIsBetweenTheTwo() {
        assertArrayEquals(new float[] {0, 0.015625f, 7, 3.96875f}, PictureFit.place(7, 4, 1920, 1080, 0.5f), DELTA);
    }

    @Test
    void straighteningIsClamped() {
        assertArrayEquals(PictureFit.place(7, 4, 1920, 1080, 0), PictureFit.place(7, 4, 1920, 1080, -1), DELTA);
        assertArrayEquals(PictureFit.place(7, 4, 1920, 1080, 1), PictureFit.place(7, 4, 1920, 1080, 2), DELTA);
    }

    @Test
    void aWiderAreaCentersTheTrueShapeSideways() {
        assertArrayEquals(new float[] {0.16667f, 0, 10.66667f, 6}, PictureFit.place(11, 6, 1920, 1080, 1), DELTA);
    }

    @Test
    void tooFarToStretchKeepsItsTrueShape() {
        float[] expected = {0, 0.09375f, 5, 2.8125f};
        assertArrayEquals(expected, PictureFit.place(5, 3, 1920, 1080, 0), DELTA);
        assertArrayEquals(expected, PictureFit.place(5, 3, 1920, 1080, 1), DELTA);
    }

    @Test
    void nearestRowsRoundsTheIdealHeight() {
        assertEquals(4, PictureFit.nearestRows(7, 1 / 16f, 1920, 1080));
        assertEquals(4, PictureFit.nearestRows(7, 0, 1920, 1080));
        assertEquals(6, PictureFit.nearestRows(11, 1 / 16f, 1920, 1080));
        assertEquals(2, PictureFit.nearestRows(1, 1 / 16f, 1080, 1920));
    }

    @Test
    void nearestRowsIsAtLeastOne() {
        assertEquals(1, PictureFit.nearestRows(1, 0, 1920, 1080));
    }

    @Test
    void aWidePictureSetsTheCameraDistanceByItsWidth() {
        assertEquals(1.3798, PictureFit.glideDistance(7, 3.9375f, 110, 1920 / 1081.0), 1e-3);
    }

    @Test
    void aWiderFieldOfViewBringsTheCameraCloser() {
        // Flying at FOV 110 draws at 121: the camera has to come closer for the same picture to fill the view.
        assertEquals(1.1149, PictureFit.glideDistance(7, 3.9375f, 121, 1920 / 1081.0), 1e-3);
    }

    @Test
    void aTallPictureSetsTheCameraDistanceByItsHeight() {
        assertEquals(1.5, PictureFit.glideDistance(4, 3, 90, 16 / 9.0), 1e-9);
    }

    private static final float LIFT = 0.002f;

    @Test
    void edgesMoveInForAViewerBetweenThem() {
        assertEquals(0.006f, PictureFit.lowEdge(0, 3, 1, LIFT), 1e-6f);
        assertEquals(5.994f, PictureFit.highEdge(6, 3, 1, LIFT), 1e-5f);
    }

    @Test
    void edgesMoveInMoreFromCloseUp() {
        assertEquals(0.012f, PictureFit.lowEdge(0, 3, 0.5f, LIFT), 1e-6f);
    }

    @Test
    void edgesBarelyMoveFromFarAway() {
        assertEquals(0.00006f, PictureFit.lowEdge(0, 3, 100, LIFT), 1e-7f);
    }

    @Test
    void anEdgeDoesNotMoveOutForAViewerBeyondIt() {
        assertEquals(0, PictureFit.lowEdge(0, -2, 1, LIFT));
        assertEquals(6, PictureFit.highEdge(6, 8, 1, LIFT));
    }

    @Test
    void anEdgeStaysForAViewerInLineWithIt() {
        assertEquals(0, PictureFit.lowEdge(0, 0, 1, LIFT));
        assertEquals(6, PictureFit.highEdge(6, 6, 1, LIFT));
    }

    @Test
    void edgesStayForAViewerNotInFrontOfThePicture() {
        assertEquals(0, PictureFit.lowEdge(0, 3, LIFT, LIFT));
        assertEquals(0, PictureFit.lowEdge(0, 3, -1, LIFT));
        assertEquals(6, PictureFit.highEdge(6, 3, LIFT, LIFT));
        assertEquals(6, PictureFit.highEdge(6, 3, -1, LIFT));
    }
}
