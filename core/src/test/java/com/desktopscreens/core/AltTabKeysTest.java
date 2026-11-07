package com.desktopscreens.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AltTabKeysTest {
    private static final int VK_TAB = 0x09, VK_A = 0x41, VK_MENU = 0x12, VK_LMENU = 0xA4, VK_RMENU = 0xA5;
    private static final long SECOND = 1000000000L;

    private final AltTabKeys keys = new AltTabKeys();

    /** Alt down, Tab down and up, at {@code now}: the switcher is up, with Alt still held. */
    private void tabWithAlt(int alt, long now) {
        keys.key(alt, true, true, now);
        keys.key(VK_TAB, true, true, now);
        keys.key(VK_TAB, false, true, now);
    }

    @Test
    void countsWhileAltIsStillHeld() {
        tabWithAlt(VK_LMENU, 0);
        // Looking through the switcher can take as long as it takes.
        assertTrue(keys.justUsed(60 * SECOND));
    }

    @Test
    void countsForAMomentAfterAltIsLetGo() {
        tabWithAlt(VK_LMENU, 0);
        keys.key(VK_LMENU, false, false, 10 * SECOND);
        assertTrue(keys.justUsed(11 * SECOND));
    }

    @Test
    void stopsCountingAWhileAfterAltIsLetGo() {
        tabWithAlt(VK_LMENU, 0);
        keys.key(VK_LMENU, false, false, 10 * SECOND);
        assertFalse(keys.justUsed(12 * SECOND));
    }

    @Test
    void rightAltAndAProgramsAltCountToo() {
        tabWithAlt(VK_RMENU, 0);
        keys.key(VK_RMENU, false, false, 0);
        assertTrue(keys.justUsed(1));
        keys.clear();
        tabWithAlt(VK_MENU, 0);
        keys.key(VK_MENU, false, false, 0);
        assertTrue(keys.justUsed(1));
    }

    @Test
    void tabWithoutAltDoesNotCount() {
        keys.key(VK_TAB, true, false, 0);
        keys.key(VK_TAB, false, false, 0);
        assertFalse(keys.justUsed(1));
    }

    @Test
    void altWithoutTabDoesNotCount() {
        keys.key(VK_LMENU, true, true, 0);
        keys.key(VK_LMENU, false, false, 0);
        assertFalse(keys.justUsed(1));
    }

    @Test
    void anotherKeyWithAltDoesNotCount() {
        keys.key(VK_LMENU, true, true, 0);
        keys.key(VK_A, true, true, 0); // Alt+A, a program's own menu shortcut
        keys.key(VK_A, false, true, 0);
        keys.key(VK_LMENU, false, false, 0);
        assertFalse(keys.justUsed(1));
    }

    @Test
    void aLateTabKeyUpStartsNothing() {
        tabWithAlt(VK_LMENU, 0);
        keys.key(VK_LMENU, false, false, 0);
        keys.clear();
        keys.key(VK_TAB, false, false, 60 * SECOND);
        assertFalse(keys.justUsed(60 * SECOND));
    }

    @Test
    void clearForgetsAFinishedSwitch() {
        tabWithAlt(VK_LMENU, 0);
        keys.key(VK_LMENU, false, false, 0);
        keys.clear();
        assertFalse(keys.justUsed(1));
    }

    @Test
    void clearForgetsASwitchWithAltStillHeld() {
        tabWithAlt(VK_LMENU, 0);
        keys.clear();
        assertFalse(keys.justUsed(1));
        // Alt let go afterwards isn't a new switch either.
        keys.key(VK_LMENU, false, false, 2);
        assertFalse(keys.justUsed(3));
    }

    @Test
    void pressingTabAgainAfterClearCounts() {
        tabWithAlt(VK_LMENU, 0);
        keys.clear();
        keys.key(VK_TAB, true, true, 1);
        assertTrue(keys.justUsed(2));
    }
}
