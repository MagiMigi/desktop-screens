package com.desktopscreens.core;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.ByteByReference;
import com.sun.jna.ptr.IntByReference;

/**
 * Keeps browsers drawing behind a windowed game. Chrome, Edge and Firefox stop drawing a window that other windows
 * cover completely, to save power, which freezes a video behind the game in any capture of that monitor (seen
 * 2026-09-24 with two windowed dev clients side by side). Chromium's check (gfx::IsWindowVisibleAndFullyOpaque)
 * doesn't count click-through windows, layered windows that are less than fully opaque or have a color key, or popup
 * windows, and fullscreen Minecraft is a popup; a windowed one counts. So while the game's own monitor is captured and
 * the game is windowed, the game window is made layered with an opacity of 254 of 255: what's behind changes a pixel
 * by at most one step, which nobody can see.
 *
 * <p>The first version used the in-place view's color key instead, and a ring of sky turned into a hole
 * showing Chrome (a dark sky's gradient passes through exactly that near-black color).
 *
 * <p>The in-place view switches the same styles on and off (see {@link ClickThrough}), so this is called every frame
 * and puts it back when it's gone. Render thread only.
 */
public final class BrowserOcclusion {
    private static final int GWL_STYLE = -16, GWL_EXSTYLE = -20, WS_POPUP = 0x80000000;
    private static final int WS_EX_TRANSPARENT = 0x20, WS_EX_LAYERED = 0x80000, LWA_COLORKEY = 1, LWA_ALPHA = 2;
    private static final byte ALPHA = (byte) 254;

    /** Whether we made the window layered (and so take it back). */
    private static boolean added;

    private BrowserOcclusion() {}

    /** Every frame: {@code wanted} while the game's monitor is captured. Returns true if it changed the window's styles. */
    public static boolean keep(long window, boolean wanted) {
        HWND w = Win32.hwnd(window);
        if (w == null || !User32.INSTANCE.IsWindow(w)) return false;
        int ex = User32.INSTANCE.GetWindowLong(w, GWL_EXSTYLE);
        boolean popup = (User32.INSTANCE.GetWindowLong(w, GWL_STYLE) & WS_POPUP) != 0; // fullscreen: browsers ignore it anyway
        if (wanted && !popup) {
            if ((ex & WS_EX_LAYERED) == 0) {
                User32.INSTANCE.SetWindowLong(w, GWL_EXSTYLE, ex | WS_EX_LAYERED);
                added = true;
                setAlpha(w);
                return true;
            }
            if (!ignoredByBrowsers(w)) setAlpha(w);
            return false;
        }
        // The in-place view (click-through) has its own layered attributes and takes them away itself.
        if (added && (ex & WS_EX_TRANSPARENT) == 0) {
            added = false;
            if ((ex & WS_EX_LAYERED) != 0) {
                User32.INSTANCE.SetWindowLong(w, GWL_EXSTYLE, ex & ~WS_EX_LAYERED);
                return true;
            }
        }
        return false;
    }

    private static void setAlpha(HWND w) {
        User32.INSTANCE.SetLayeredWindowAttributes(w, 0, ALPHA, LWA_ALPHA);
    }

    /** Layered attributes browsers don't count as covering: a color key, or less than full opacity. */
    private static boolean ignoredByBrowsers(HWND w) {
        IntByReference key = new IntByReference(), flags = new IntByReference();
        ByteByReference alpha = new ByteByReference();
        if (!User32.INSTANCE.GetLayeredWindowAttributes(w, key, alpha, flags)) return false;
        int f = flags.getValue();
        return (f & LWA_COLORKEY) != 0 || (f & LWA_ALPHA) != 0 && (alpha.getValue() & 0xFF) < 255;
    }
}
