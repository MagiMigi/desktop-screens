package com.desktopscreens.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.desktopscreens.core.OpenKeyRule.Result;
import org.junit.jupiter.api.Test;

class OpenKeyRuleTest {
    /** A press of the key, by name so each test reads as a situation. */
    private static final class Press {
        boolean debugHeld, atScreen, hasTablet, hostHeld, keyFree, vr;

        Result result() {
            return OpenKeyRule.press(debugHeld, atScreen, hasTablet, hostHeld, keyFree, vr);
        }
    }

    private final Press press = new Press();

    @Test
    void atAScreenTheKeyIsTheScreensEvenWithAnotherModOnIt() {
        press.atScreen = true;
        press.keyFree = false;
        assertEquals(Result.SCREEN, press.result());
    }

    @Test
    void aScreenGoesBeforeATablet() {
        press.atScreen = true;
        press.hasTablet = true;
        press.keyFree = true;
        assertEquals(Result.SCREEN, press.result());
    }

    @Test
    void aTabletOpensOnTheKeyAloneWhenItIsFree() {
        press.hasTablet = true;
        press.keyFree = true;
        assertEquals(Result.TABLET, press.result());
    }

    @Test
    void aTabletLeavesTheKeyToTheOtherModWhenItIsNotFree() {
        press.hasTablet = true;
        press.keyFree = false;
        assertEquals(Result.PASS, press.result());
    }

    @Test
    void theHostKeyOpensATabletEvenWithAnotherModOnTheKey() {
        press.hasTablet = true;
        press.keyFree = false;
        press.hostHeld = true;
        assertEquals(Result.TABLET, press.result());
    }

    @Test
    void withoutATabletOrAScreenTheKeyIsLeftAlone() {
        press.keyFree = true;
        assertEquals(Result.PASS, press.result());
    }

    @Test
    void theHostKeyWithoutATabletLeavesTheKeyAlone() {
        press.keyFree = false;
        press.hostHeld = true;
        assertEquals(Result.PASS, press.result());
    }

    @Test
    void aMinecraftDebugComboIsNeverTaken() {
        // F3 + G draws the chunk borders, also while looking at a screen.
        press.debugHeld = true;
        press.atScreen = true;
        press.hasTablet = true;
        press.keyFree = true;
        press.hostHeld = true;
        assertEquals(Result.PASS, press.result());
    }

    @Test
    void inVrATabletIsLeftAsItWasEvenWithAFreeKey() {
        // No keyboard for the host key there: the press goes on to every binding, and the mod's own opens the Tablet.
        press.vr = true;
        press.hasTablet = true;
        press.keyFree = true;
        assertEquals(Result.PASS, press.result());
    }

    @Test
    void inVrAScreenStillTakesTheKey() {
        // A radial pick of "Use the screen" presses the real key: the other mods stay quiet at a screen.
        press.vr = true;
        press.atScreen = true;
        assertEquals(Result.SCREEN, press.result());
    }

    @Test
    void anotherModsBindingCountsAsOther() {
        assertTrue(OpenKeyRule.countsAsOther("key.oritech.augment_screen"));
        assertTrue(OpenKeyRule.countsAsOther("key.curios.open.desc"));
    }

    @Test
    void minecraftsOwnBindingsOnTheKeyDoNotCountAsOther() {
        // On 26.x these two sit on G by default, so G would never be free.
        assertFalse(OpenKeyRule.countsAsOther("key.quickActions"));
        assertFalse(OpenKeyRule.countsAsOther("key.debug.showChunkBorders"));
    }

    @Test
    void aModsNameThatOnlyLooksSimilarCountsAsOther() {
        assertTrue(OpenKeyRule.countsAsOther("key.quickActionsPlus"));
        assertTrue(OpenKeyRule.countsAsOther("key.debugger.toggle"));
    }
}
