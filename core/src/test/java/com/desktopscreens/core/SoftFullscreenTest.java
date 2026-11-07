package com.desktopscreens.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

class SoftFullscreenTest {
    /** A 1920x1080 monitor, {left, top, right, bottom}. */
    private static final int[] MONITOR = {0, 0, 1920, 1080};
    private static final int[] TALLER = {0, 0, 1920, 1081}, WIDER = {0, 0, 1921, 1080};
    private static final List<int[]> NO_OTHERS = Collections.emptyList();

    private static int[] grown(int[] drawingArea, List<int[]> others, boolean preferRight) {
        return SoftFullscreen.grown(drawingArea, MONITOR, others, preferRight);
    }

    @Test
    void anExactWindowGrowsDown() {
        assertArrayEquals(TALLER, grown(MONITOR, NO_OTHERS, false));
    }

    @Test
    void itCountsFromTheMonitorsOwnPlace() {
        int[] left = {-1024, 176, 0, 944};
        assertArrayEquals(new int[] {-1024, 176, 0, 945}, SoftFullscreen.grown(left, left, NO_OTHERS, false));
    }

    @Test
    void aWindowAlreadyBiggerStaysAsItIs() {
        // Minecraft's own F11 on 26.1.2 and newer, or this mod's from an earlier tick.
        assertNull(grown(TALLER, NO_OTHERS, false));
        assertNull(grown(WIDER, NO_OTHERS, false));
    }

    @Test
    void aSmallerWindowStaysAsItIs() {
        // A windowed game, or one kept a pixel short while clicks go through it.
        assertNull(grown(new int[] {0, 0, 1920, 1079}, NO_OTHERS, false));
        assertNull(grown(new int[] {100, 100, 900, 700}, NO_OTHERS, false));
    }

    @Test
    void aMinimizedWindowStaysAsItIs() {
        assertNull(grown(new int[] {-32000, -32000, -31840, -31972}, NO_OTHERS, false));
    }

    @Test
    void aMonitorDirectlyBelowSendsItRight() {
        int[] below = {0, 1080, 1920, 2160};
        assertArrayEquals(WIDER, grown(MONITOR, Arrays.asList(below), false));
    }

    @Test
    void aMonitorBelowThatOnlyPartlyOverlapsSendsItRightToo() {
        int[] below = {1900, 1080, 3820, 2160};
        assertArrayEquals(WIDER, grown(MONITOR, Arrays.asList(below), false));
    }

    @Test
    void aMonitorOnlyTouchingTheCornerIsNotInTheWay() {
        int[] diagonal = {1920, 1080, 3840, 2160};
        assertArrayEquals(TALLER, grown(MONITOR, Arrays.asList(diagonal), false));
    }

    @Test
    void aMonitorOnTheLeftOrAboveIsNotInTheWay() {
        int[] left = {-1024, 176, 0, 944}, above = {0, -1080, 1920, 0};
        assertArrayEquals(TALLER, grown(MONITOR, Arrays.asList(left, above), false));
    }

    @Test
    void aMonitorOnTheRightIsNotInTheWayOfGrowingDown() {
        int[] right = {1920, 0, 3840, 1080};
        assertArrayEquals(TALLER, grown(MONITOR, Arrays.asList(right), false));
    }

    @Test
    void withMonitorsBelowAndRightItGrowsDownAnyway() {
        int[] below = {0, 1080, 1920, 2160}, right = {1920, 0, 3840, 1080};
        assertArrayEquals(TALLER, grown(MONITOR, Arrays.asList(below, right), false));
    }

    @Test
    void preferringRightGrowsRight() {
        assertArrayEquals(WIDER, grown(MONITOR, NO_OTHERS, true));
    }

    @Test
    void preferringRightWithAMonitorThereGrowsDown() {
        int[] right = {1920, 0, 3840, 1080};
        assertArrayEquals(TALLER, grown(MONITOR, Arrays.asList(right), true));
    }

    @Test
    void preferringRightWithMonitorsOnBothSidesGrowsRightAnyway() {
        int[] below = {0, 1080, 1920, 2160}, right = {1920, 0, 3840, 1080};
        assertArrayEquals(WIDER, grown(MONITOR, Arrays.asList(below, right), true));
    }
}
