package com.desktopscreens.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class MenuAreaTest {
    /** A window, a menu sticking out under it, the same menu shorter (a submenu closed), and a submenu to its right. */
    private static final int[] W = {0, 0, 100, 100}, M1 = {50, 80, 120, 180}, SMALL = {50, 80, 120, 140}, SUB = {120, 90, 220, 150};

    private final MenuArea area = new MenuArea();
    /** The clock, in milliseconds. */
    private long now = 1000;

    private static List<int[]> menus(int[]... rects) {
        return Arrays.asList(rects);
    }

    private static final List<int[]> NONE = Collections.emptyList();

    /** The area once these menus have stayed where they are long enough to count. */
    private int[] settled(int[] window, List<int[]> menus, boolean buttonHeld) {
        area.update(window, menus, buttonHeld, now);
        now += MenuArea.SETTLE_MILLIS;
        return area.update(window, menus, buttonHeld, now);
    }

    @Test
    void noMenusShowsTheWindow() {
        assertArrayEquals(new int[] {0, 0, 100, 100}, settled(W, NONE, false));
    }

    @Test
    void aMenuStickingOutGrowsTheArea() {
        assertArrayEquals(new int[] {0, 0, 120, 180}, settled(W, menus(M1), false));
    }

    @Test
    void aMenuCountsOnlyOnceItStaysPut() {
        assertArrayEquals(new int[] {0, 0, 100, 100}, area.update(W, menus(M1), false, now));
        assertArrayEquals(new int[] {0, 0, 100, 100}, area.update(W, menus(M1), false, now + MenuArea.SETTLE_MILLIS - 1));
        assertArrayEquals(new int[] {0, 0, 120, 180}, area.update(W, menus(M1), false, now + MenuArea.SETTLE_MILLIS));
    }

    @Test
    void aReusedMenuSeenAtItsOldPlaceForAMomentIsLeftOut() {
        // Win11 Notepad shows its menu window at the last menu's place for a moment, then moves it (2026-11-01).
        int[] inside = {10, 10, 50, 50};
        area.update(W, menus(M1), false, now);
        area.update(W, menus(inside), false, now + 30);
        assertArrayEquals(new int[] {0, 0, 100, 100}, area.update(W, menus(inside), false, now + 30 + MenuArea.SETTLE_MILLIS));
    }

    @Test
    void aMenuNotSettledYetKeepsTheAreaItHas() {
        settled(W, menus(M1), false);
        assertArrayEquals(new int[] {0, 0, 120, 180}, area.update(W, menus(new int[] {50, 80, 130, 200}), false, now + 1));
    }

    @Test
    void itOnlyGrowsWhileMenusStayOpen() {
        settled(W, menus(M1), false);
        assertArrayEquals(new int[] {0, 0, 120, 180}, settled(W, menus(SMALL), false));
        assertArrayEquals(new int[] {0, 0, 220, 180}, settled(W, menus(SMALL, SUB), false));
    }

    @Test
    void theLastMenuClosingGoesBackToTheWindowAMomentLater() {
        settled(W, menus(M1, SUB), false);
        assertArrayEquals(new int[] {0, 0, 220, 180}, area.update(W, NONE, false, now + 1));
        assertArrayEquals(new int[] {0, 0, 100, 100}, area.update(W, NONE, false, now + MenuArea.CLOSE_GRACE_MILLIS));
    }

    @Test
    void slidingAlongAMenuBarDoesntPump() {
        // One menu closes a moment before the next one opens (Win11 Notepad's menu bar, 2026-11-01).
        int[] next = {60, 80, 140, 200};
        settled(W, menus(M1), false);
        assertArrayEquals(new int[] {0, 0, 120, 180}, area.update(W, NONE, false, now + 5));
        assertArrayEquals(new int[] {0, 0, 120, 180}, area.update(W, menus(next), false, now + 10));
        assertArrayEquals(new int[] {0, 0, 140, 200}, area.update(W, menus(next), false, now + 10 + MenuArea.SETTLE_MILLIS));
    }

    @Test
    void aHeldButtonWaitsUntilLetGo() {
        assertArrayEquals(new int[] {0, 0, 100, 100}, settled(W, menus(M1), true));
        assertArrayEquals(new int[] {0, 0, 120, 180}, area.update(W, menus(M1), false, now + 1));
    }

    @Test
    void aHeldButtonKeepsTheAreaItHas() {
        settled(W, menus(M1), false);
        assertArrayEquals(new int[] {0, 0, 120, 180}, settled(W, menus(M1, SUB), true));
        assertArrayEquals(new int[] {0, 0, 220, 180}, area.update(W, menus(M1, SUB), false, now + 1));
    }

    @Test
    void aMenuInsideTheWindowChangesNothing() {
        assertArrayEquals(new int[] {0, 0, 100, 100}, settled(W, menus(new int[] {10, 10, 50, 50}), false));
    }

    @Test
    void aPopupAwayFromTheWindowIsLeftOut() {
        assertArrayEquals(new int[] {0, 0, 100, 100}, settled(W, menus(new int[] {300, 300, 400, 400}), false));
    }

    @Test
    void aSubmenuOfAMenuOutsideTheWindowCounts() {
        // Touches M1 (its right edge) but not the window; a few pixels apart still count as touching.
        assertArrayEquals(new int[] {0, 0, 220, 250}, settled(W, menus(M1, new int[] {124, 150, 220, 250}), false));
    }

    @Test
    void aMovedWindowStartsOver() {
        settled(W, menus(M1), false);
        assertArrayEquals(new int[] {10, 0, 120, 180}, area.update(new int[] {10, 0, 110, 100}, menus(M1), false, now + 1));
    }

    @Test
    void aMinimizedWindowShowsNothingAndForgets() {
        settled(W, menus(M1), false);
        assertNull(area.update(null, menus(M1), false, now + 1));
        assertArrayEquals(new int[] {0, 0, 100, 100}, area.update(W, menus(M1), true, now + 2));
    }

    @Test
    void resetForgetsTheGrownArea() {
        settled(W, menus(M1), false);
        area.reset();
        assertArrayEquals(new int[] {0, 0, 100, 100}, area.update(W, menus(M1), true, now + 1));
    }
}
