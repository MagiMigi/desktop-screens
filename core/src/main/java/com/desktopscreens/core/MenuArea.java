package com.desktopscreens.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a one-window picture shows while that window's program has menus open: the window and the parts of its menus
 * sticking out of it, so a right-click menu near its edge isn't cut off (2026-11-01). It only grows while menus stay
 * open, so moving through submenus doesn't make the picture pump in and out, and it waits while a button is held
 * through the mod: a zoom moves the cursor under a still VR laser, and letting go would pick whatever is there by then.
 * Rectangles are {left, top, right, bottom} in desktop pixels.
 */
public final class MenuArea {
    /** How far apart (desktop pixels) a menu and the window or another menu may be and still count as touching. */
    private static final int NEAR = 8;

    /**
     * How long a menu has to stay where it is before it counts. Win11 Notepad reuses its menu window: opened again, it
     * shows up at the last menu's place for a moment (under 60 ms in the test) and then moves, and as the area only
     * grows, that place stayed in it for as long as the menu was open (2026-11-01).
     */
    static final long SETTLE_MILLIS = 100;

    /**
     * How long the area waits, once the last menu closed, before it goes back to the window. Sliding along a menu bar
     * closes one menu a moment before the next opens, and the picture zoomed in and out in between (Win11 Notepad,
     * Test 62 again, 2026-11-01).
     */
    static final long CLOSE_GRACE_MILLIS = 250;

    /** The window the last area was worked out for, and that area; null before the first one. */
    private int[] window, shown;
    /** Since when each open menu has been where it is, by its rectangle. */
    private Map<String, Long> since = new HashMap<String, Long>();
    /** When a menu was last seen open; null since the start. */
    private Long lastOpen;

    /**
     * The area to show now: {@code window} with the {@code menus} sticking out of it, never smaller than the last one
     * while menus stay open; the last one unchanged while {@code buttonHeld}. Null for a null window (minimized), which
     * also forgets the last area; a window that moved or changed size starts over. Only menus touching the window, or
     * a menu that does (a submenu), count: the program's other popups elsewhere (Explorer's taskbar flyouts) don't. A
     * menu counts once it has stayed put for {@link #SETTLE_MILLIS}, and the area goes back to the window
     * {@link #CLOSE_GRACE_MILLIS} after the last one closed; {@code nowMillis} is any steady clock.
     */
    public int[] update(int[] window, List<int[]> menus, boolean buttonHeld, long nowMillis) {
        if (window == null) {
            reset();
            return null;
        }
        Map<String, Long> seen = new HashMap<String, Long>();
        for (int[] menu : menus) {
            String key = Arrays.toString(menu);
            Long first = since.get(key);
            seen.put(key, first != null ? first : nowMillis);
        }
        since = seen;
        if (!Arrays.equals(this.window, window)) {
            this.window = window.clone();
            shown = window.clone();
        }
        List<int[]> open = touching(window, menus);
        if (!open.isEmpty()) lastOpen = nowMillis;
        if (!buttonHeld) {
            if (open.isEmpty() && (lastOpen == null || nowMillis - lastOpen >= CLOSE_GRACE_MILLIS)) shown = window.clone();
            for (int[] menu : open) {
                if (nowMillis - since.get(Arrays.toString(menu)) >= SETTLE_MILLIS) shown = union(shown, menu);
            }
        }
        return shown.clone();
    }

    /** Starts over: the next area is the window's. */
    public void reset() {
        window = null;
        shown = null;
        since = new HashMap<String, Long>();
        lastOpen = null;
    }

    /** The menus touching the window, or touching one that does, and so on. */
    private static List<int[]> touching(int[] window, List<int[]> menus) {
        List<int[]> open = new ArrayList<int[]>();
        boolean[] taken = new boolean[menus.size()];
        boolean more = true;
        while (more) {
            more = false;
            for (int i = 0; i < taken.length; i++) {
                if (taken[i]) continue;
                int[] menu = menus.get(i);
                boolean touches = near(window, menu);
                for (int j = 0; !touches && j < open.size(); j++) touches = near(open.get(j), menu);
                if (touches) {
                    taken[i] = true;
                    open.add(menu);
                    more = true;
                }
            }
        }
        return open;
    }

    private static boolean near(int[] a, int[] b) {
        return a[0] - NEAR <= b[2] && b[0] - NEAR <= a[2] && a[1] - NEAR <= b[3] && b[1] - NEAR <= a[3];
    }

    private static int[] union(int[] a, int[] b) {
        return new int[] {Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])};
    }
}
