package com.desktopscreens.core;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.ByteByReference;
import com.sun.jna.ptr.IntByReference;

/**
 * Lets clicks go through a window to the windows behind it, while keeping it on top of them. This is how
 * the monitor the game is on can be controlled: the real apps behind the game get the mouse. Wherever the game
 * draws {@link #SEE_THROUGH_RGB}, Windows shows the real screen behind it instead (a color key), so that part
 * needs no capture at all: it's live, and protected video (DRM), which every screen capture shows black, plays
 * there as usual. {@link #close} puts the window back the way it was.
 */
public final class ClickThrough implements AutoCloseable {
    /**
     * Pixels of exactly this color (0xRRGGBB) show what's behind the window. Almost black, so the game's
     * see-through hint boxes drawn over it come out plain black instead of a visible tint.
     */
    public static final int SEE_THROUGH_RGB = 0x010001;

    private static final int GWL_EXSTYLE = -20, WS_EX_TOPMOST = 0x8, WS_EX_TRANSPARENT = 0x20, WS_EX_LAYERED = 0x80000;
    private static final int LWA_COLORKEY = 1, LWA_ALPHA = 2, SWP_NOSIZE = 0x1, SWP_NOMOVE = 0x2, SWP_NOZORDER = 0x4, SWP_NOACTIVATE = 0x10;
    // Must be long: createConstant(int) zero-extends, and a 32-bit -1 is no window at all on 64-bit Windows.
    private static final HWND HWND_TOPMOST = new HWND(Pointer.createConstant(-1L));
    private static final HWND HWND_NOTOPMOST = new HWND(Pointer.createConstant(-2L));

    private final HWND window;
    private final int added; // the styles this added, so close() takes back only those
    private final int[] layeredBefore; // {key, alpha, flags}, only if the window was layered already
    private final boolean madeTopmost;
    private final RECT fullSize; // only if it was shortened
    private boolean closed;

    public ClickThrough(long window) {
        this.window = Win32.hwnd(window);
        int ex = User32.INSTANCE.GetWindowLong(this.window, GWL_EXSTYLE);
        added = (WS_EX_LAYERED | WS_EX_TRANSPARENT) & ~ex;
        layeredBefore = (ex & WS_EX_LAYERED) != 0 ? layeredAttributes(this.window) : null;
        User32.INSTANCE.SetWindowLong(this.window, GWL_EXSTYLE, ex | WS_EX_LAYERED | WS_EX_TRANSPARENT);
        // A layered window shows nothing until it has layered attributes: fully opaque, except the see-through color.
        // COLORREF is 0x00BBGGRR; SEE_THROUGH_RGB reads the same either way round.
        User32.INSTANCE.SetLayeredWindowAttributes(this.window, SEE_THROUGH_RGB, (byte) 255, LWA_ALPHA | LWA_COLORKEY);
        // A windowed game has to be put on top, or the app you click would come in front of it. Fullscreen ones usually are already.
        madeTopmost = (ex & WS_EX_TOPMOST) == 0
                && User32.INSTANCE.SetWindowPos(this.window, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
        fullSize = shortenIfFullscreen(this.window);
    }

    /**
     * A window covering its whole monitor counts as a fullscreen app, and Windows keeps the taskbar below other
     * windows while one is there: an auto-hide taskbar then can't be brought up over the apps behind the game.
     * One pixel short is enough to stop that, and at 1:1 the uncovered row shows the same as the game would.
     * Returns the full size to go back to, or null if it wasn't fullscreen. It resizes the game, so on the
     * game's own thread only.
     */
    private static RECT shortenIfFullscreen(HWND w) {
        RECT r = new RECT();
        WinUser.MONITORINFO m = new WinUser.MONITORINFO();
        if (!User32.INSTANCE.GetWindowRect(w, r)
                || !User32.INSTANCE.GetMonitorInfo(User32.INSTANCE.MonitorFromWindow(w, Win32.MONITOR_DEFAULTTONEAREST), m).booleanValue()) {
            return null;
        }
        RECT mon = m.rcMonitor;
        if (r.left > mon.left || r.top > mon.top || r.right < mon.right || r.bottom < mon.bottom) return null;
        User32.INSTANCE.SetWindowPos(w, null, mon.left, mon.top, mon.right - mon.left, mon.bottom - mon.top - 1,
                SWP_NOZORDER | SWP_NOACTIVATE);
        return r;
    }

    private static int[] layeredAttributes(HWND w) {
        IntByReference key = new IntByReference(), flags = new IntByReference();
        ByteByReference alpha = new ByteByReference((byte) 255);
        User32.INSTANCE.GetLayeredWindowAttributes(w, key, alpha, flags);
        return new int[] {key.getValue(), alpha.getValue() & 0xFF, flags.getValue()};
    }

    /** Whether the window is above normal windows right now. Without that, a clicked app comes in front of it. */
    public boolean isTopmost() {
        return (User32.INSTANCE.GetWindowLong(window, GWL_EXSTYLE) & WS_EX_TOPMOST) != 0;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        int ex = User32.INSTANCE.GetWindowLong(window, GWL_EXSTYLE);
        User32.INSTANCE.SetWindowLong(window, GWL_EXSTYLE, ex & ~added); // dropping WS_EX_LAYERED drops its attributes too
        if (layeredBefore != null) {
            User32.INSTANCE.SetLayeredWindowAttributes(window, layeredBefore[0], (byte) layeredBefore[1], layeredBefore[2]);
        }
        if (madeTopmost) User32.INSTANCE.SetWindowPos(window, HWND_NOTOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
        if (fullSize != null) {
            User32.INSTANCE.SetWindowPos(window, null, fullSize.left, fullSize.top, fullSize.right - fullSize.left,
                    fullSize.bottom - fullSize.top, SWP_NOZORDER | SWP_NOACTIVATE);
        }
    }

    @Override
    public String toString() {
        return "topmost: " + isTopmost() + (madeTopmost ? " (made topmost)" : " (was already)")
                + (fullSize != null ? ", 1 px short of fullscreen" : "");
    }
}
