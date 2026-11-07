package com.desktopscreens.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RepeatingReportTest {
    private static final long SECOND = 1000000000L;

    private final RepeatingReport report = new RepeatingReport();

    @Test
    void theFirstReportShows() {
        assertTrue(report.show(10 * SECOND, false));
    }

    @Test
    void laterReportsStayOutOfTheLogWhileAllIsWell() {
        report.show(10 * SECOND, false);
        assertFalse(report.show(20 * SECOND, false));
        assertFalse(report.show(30 * SECOND, false));
        // Not even an hour later: this is what used to fill the console.
        assertFalse(report.show(3600 * SECOND, false));
    }

    @Test
    void troubleShowsRightAfterTheFirstReport() {
        report.show(10 * SECOND, false);
        assertTrue(report.show(20 * SECOND, true));
    }

    @Test
    void troubleInTheFirstReportCountsAsShown() {
        assertTrue(report.show(10 * SECOND, true));
        assertFalse(report.show(20 * SECOND, true));
    }

    @Test
    void troubleThatGoesOnShowsOnceAMinute() {
        report.show(10 * SECOND, false);
        assertTrue(report.show(20 * SECOND, true));
        assertFalse(report.show(30 * SECOND, true));
        assertFalse(report.show(79 * SECOND, true));
        assertTrue(report.show(80 * SECOND, true));
        assertFalse(report.show(90 * SECOND, true));
    }

    @Test
    void reportsWithoutTroubleDontRestartTheMinute() {
        report.show(10 * SECOND, false);
        report.show(20 * SECOND, true);
        report.show(70 * SECOND, false);
        assertTrue(report.show(80 * SECOND, true));
    }

    @Test
    void aQuietReportAfterTroubleStaysOutOfTheLog() {
        report.show(10 * SECOND, false);
        report.show(20 * SECOND, true);
        assertFalse(report.show(200 * SECOND, false));
    }

    /** System.nanoTime() can be any number, also 0 or below, so no time may stand for "not yet". */
    @Test
    void worksWhenTheClockIsZeroOrBelow() {
        assertTrue(report.show(-100 * SECOND, false));
        assertTrue(report.show(0, true));
        assertFalse(report.show(10 * SECOND, true));
        assertTrue(report.show(60 * SECOND, true));
    }

    @Test
    void eachStreamCountsOnItsOwn() {
        report.show(10 * SECOND, true);
        RepeatingReport other = new RepeatingReport();
        assertTrue(other.show(20 * SECOND, false));
        assertTrue(new RepeatingReport().show(20 * SECOND, true));
    }
}
