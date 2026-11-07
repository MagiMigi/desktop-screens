package com.desktopscreens.core;

/**
 * Whether Alt+Tab was used just now, going by the keys the keyboard hook sees. A window getting the focus looks the
 * same to the session whether you picked it, clicked it or it took the focus by itself, and only a window you picked
 * replaces the one window shown. Times are from any steady clock, in nanoseconds. One thread only.
 */
final class AltTabKeys {
    private static final int VK_TAB = 0x09, VK_MENU = 0x12, VK_LMENU = 0xA4, VK_RMENU = 0xA5;
    /** How long after Alt is let go a window getting the focus still comes from the switch. */
    private static final long WINDOW_NANOS = 1500000000L;

    /** Tab was pressed with Alt, and Alt is still down: the switcher is up, for as long as it takes to pick. */
    private boolean holding;
    private boolean released;
    private long releasedAt;

    /** A key went down or up; {@code altHeld}: the hook's flag that Alt is down with it. */
    void key(int vk, boolean down, boolean altHeld, long now) {
        if (vk == VK_TAB) {
            if (down && altHeld) holding = true;
        } else if (!down && holding && (vk == VK_MENU || vk == VK_LMENU || vk == VK_RMENU)) {
            holding = false;
            released = true;
            releasedAt = now;
        }
    }

    boolean justUsed(long now) {
        return holding || released && now - releasedAt < WINDOW_NANOS;
    }

    /** The switch has done its work: a window got the focus from it. */
    void clear() {
        holding = false;
        released = false;
    }
}
