package com.desktopscreens.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InputSessionTest {
    private static final int VK_PACKET = 0xE7, VK_RCONTROL = 0xA3, VK_F12 = 0x7B, LLKHF_EXTENDED = 0x1, LLKHF_INJECTED = 0x10;
    private static final int RIGHT_CTRL = 0x11D, F12 = 0x58;

    @Test
    void rightCtrlIsTheHostKey() {
        assertTrue(InputSession.isHostKey(VK_RCONTROL, 0x1D, LLKHF_EXTENDED, RIGHT_CTRL, VK_RCONTROL));
    }

    @Test
    void aTypedCharacterIsNeverTheHostKey() {
        // SendInput's Unicode characters arrive as VK_PACKET with the character where the scan code goes:
        // "ĝ" (0x11D) looks like Right Ctrl, and "X" (0x58) like F12.
        assertFalse(InputSession.isHostKey(VK_PACKET, 0x11D, LLKHF_INJECTED, RIGHT_CTRL, VK_RCONTROL));
        assertFalse(InputSession.isHostKey(VK_PACKET, 'X', LLKHF_INJECTED, F12, VK_F12));
    }

    @Test
    void ourOwnInputIsMarked() {
        assertTrue(InjectedInput.isOurs(InjectedInput.MARK));
        assertFalse(InjectedInput.isOurs(0));
    }
}
