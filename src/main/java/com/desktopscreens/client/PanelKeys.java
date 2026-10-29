package com.desktopscreens.client;

import com.mojang.blaze3d.platform.InputConstants;

import java.util.HashMap;
import java.util.Map;

/**
 * Minecraft's key codes as Windows virtual keys, for the VR panel: the keys Vivecraft's keyboard and menu buttons send
 * that aren't characters (characters go as characters, see {@link DesktopScreen}), and letters and digits for shortcuts
 * like Ctrl + C. Built from Minecraft's own names, so each version's codes apply (26.3's SDL ones too, where 0-9
 * aren't even in order).
 */
final class PanelKeys {
    private static final int VK_LSHIFT = 0xA0, VK_RSHIFT = 0xA1, VK_LCONTROL = 0xA2, VK_RCONTROL = 0xA3, VK_LMENU = 0xA4, VK_RMENU = 0xA5;
    private static final Map<Integer, Integer> VK = new HashMap<>();

    static {
        VK.put(InputConstants.KEY_BACKSPACE, 0x08);
        VK.put(InputConstants.KEY_TAB, 0x09);
        VK.put(InputConstants.KEY_RETURN, 0x0D);
        VK.put(InputConstants.KEY_NUMPADENTER, 0x0D);
        VK.put(InputConstants.KEY_PAGEUP, 0x21);
        VK.put(InputConstants.KEY_PAGEDOWN, 0x22);
        VK.put(InputConstants.KEY_END, 0x23);
        VK.put(InputConstants.KEY_HOME, 0x24);
        VK.put(InputConstants.KEY_LEFT, 0x25);
        VK.put(InputConstants.KEY_UP, 0x26);
        VK.put(InputConstants.KEY_RIGHT, 0x27);
        VK.put(InputConstants.KEY_DOWN, 0x28);
        VK.put(InputConstants.KEY_INSERT, 0x2D);
        VK.put(InputConstants.KEY_DELETE, 0x2E);
        VK.put(InputConstants.KEY_LSHIFT, VK_LSHIFT);
        VK.put(InputConstants.KEY_RSHIFT, VK_RSHIFT);
        VK.put(InputConstants.KEY_LCONTROL, VK_LCONTROL);
        VK.put(InputConstants.KEY_RCONTROL, VK_RCONTROL);
        VK.put(InputConstants.KEY_LALT, VK_LMENU);
        VK.put(InputConstants.KEY_RALT, VK_RMENU);
        int[] digits = {InputConstants.KEY_0, InputConstants.KEY_1, InputConstants.KEY_2, InputConstants.KEY_3, InputConstants.KEY_4,
                InputConstants.KEY_5, InputConstants.KEY_6, InputConstants.KEY_7, InputConstants.KEY_8, InputConstants.KEY_9};
        for (int i = 0; i < digits.length; i++) VK.put(digits[i], 0x30 + i);
        int[] letters = {InputConstants.KEY_A, InputConstants.KEY_B, InputConstants.KEY_C, InputConstants.KEY_D, InputConstants.KEY_E,
                InputConstants.KEY_F, InputConstants.KEY_G, InputConstants.KEY_H, InputConstants.KEY_I, InputConstants.KEY_J,
                InputConstants.KEY_K, InputConstants.KEY_L, InputConstants.KEY_M, InputConstants.KEY_N, InputConstants.KEY_O,
                InputConstants.KEY_P, InputConstants.KEY_Q, InputConstants.KEY_R, InputConstants.KEY_S, InputConstants.KEY_T,
                InputConstants.KEY_U, InputConstants.KEY_V, InputConstants.KEY_W, InputConstants.KEY_X, InputConstants.KEY_Y,
                InputConstants.KEY_Z};
        for (int i = 0; i < letters.length; i++) VK.put(letters[i], 0x41 + i);
    }

    private PanelKeys() {}

    /** The virtual key for a Minecraft key code, or 0 for a key that isn't sent to the PC. */
    static int vk(int key) {
        Integer vk = VK.get(key);
        return vk == null ? 0 : vk;
    }

    /** Letters and digits: typed as characters, and sent as keys only for shortcuts. */
    static boolean isCharacter(int vk) {
        return vk >= 0x30 && vk <= 0x39 || vk >= 0x41 && vk <= 0x5A;
    }

    /** Ctrl or Alt, either side: while one is held, letters and digits go as keys (shortcuts), not characters. */
    static boolean isCtrlOrAlt(int vk) {
        return vk == VK_LCONTROL || vk == VK_RCONTROL || vk == VK_LMENU || vk == VK_RMENU;
    }

    /** Shift, Ctrl or Alt, either side. */
    static boolean isModifier(int vk) {
        return vk == VK_LSHIFT || vk == VK_RSHIFT || isCtrlOrAlt(vk);
    }
}
