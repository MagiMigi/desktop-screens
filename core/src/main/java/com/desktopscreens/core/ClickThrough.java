package com.desktopscreens.core;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HRGN;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.ByteByReference;
import com.sun.jna.ptr.IntByReference;

import java.util.Arrays;
import java.util.List;

/**
 * Lets clicks go through a window to the windows behind it, while keeping it on top of them. This is how
 * the monitor the game is on can be controlled: the real apps behind the game get the mouse. Wherever the game
 * draws {@link #SEE_THROUGH_RGB}, Windows shows the real screen behind it instead (a color key), so that part
 * needs no capture at all: it's live, and protected video (DRM), which every screen capture shows black, plays
 * there as usual. Where a game's pictures skip the color key, {@link #shape} cuts the hole into the window instead.
 * {@link #close} puts the window back the way it was.
 */
public final class ClickThrough implements AutoCloseable {
    /**
     * Pixels of exactly this color (0xRRGGBB) show what's behind the window. Almost black, so the game's
     * see-through hint boxes drawn over it come out plain black instead of a visible tint.
     */
    public static final int SEE_THROUGH_RGB = 0x010001;

    private static final int GWL_EXSTYLE = -20, WS_EX_TOPMOST = 0x8, WS_EX_TRANSPARENT = 0x20, WS_EX_LAYERED = 0x80000;
    private static final int LWA_COLORKEY = 1, LWA_ALPHA = 2, SWP_NOSIZE = 0x1, SWP_NOMOVE = 0x2, SWP_NOZORDER = 0x4, SWP_NOACTIVATE = 0x10;
    private static final int RGN_OR = 2, RGN_DIFF = 4;
    private static final int DWMWA_NCRENDERING_POLICY = 2, DWMWA_EXTENDED_FRAME_BOUNDS = 9;
    private static final int DWMNCRP_USEWINDOWSTYLE = 0, DWMNCRP_DISABLED = 1, DWMNCRP_ENABLED = 2;
    /** After the window gains or loses the focus, its frame is drawn again now and once more this much later. */
    private static final long REDRAW_AGAIN_NANOS = 150_000_000L;
    // Must be long: createConstant(int) zero-extends, and a 32-bit -1 is no window at all on 64-bit Windows.
    private static final HWND HWND_TOPMOST = new HWND(Pointer.createConstant(-1L));
    private static final HWND HWND_NOTOPMOST = new HWND(Pointer.createConstant(-2L));

    private final long handle;
    private final HWND window;
    private final int added; // the styles this added, so close() takes back only those
    private final int[] layeredBefore; // {key, alpha, flags}, only if the window was layered already
    private final boolean madeTopmost;
    private final RECT fullSize; // only if it was shortened
    private boolean closed;
    // What {@link #shape} last gave Windows (the window's visible part, where its drawing area starts, the hole, the
    // pieces kept), or null.
    private int[] shaped;
    private boolean wasActive;
    private long redrawAgainAt;

    public ClickThrough(long window) {
        this.handle = window;
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
        fullSize = shortenIfFullscreen(window, this.window);
    }

    /**
     * A window whose drawing area covers its whole monitor counts as a fullscreen app, and Windows keeps the taskbar
     * below other windows while one is there: an auto-hide taskbar then can't be brought up over the apps behind the
     * game. One pixel short is enough to stop that, and at 1:1 the uncovered row shows the same as the game would.
     * The drawing area, not the window: a maximized window reaches past its monitor with its invisible resize
     * borders, but its title bar takes room, and Windows doesn't count it (a probe asked, 2026-10-02; moving it pushed
     * the game in from the monitor's edges); Minecraft 26.3's borderless F11 window keeps a frame's styles while SDL
     * hides the frame, and Windows does count it. Only the height changes: 26.3 makes that window 1 px wider than the
     * monitor on purpose. Returns the full size to go back to, or null if it wasn't fullscreen. It resizes the game,
     * so on the game's own thread only.
     */
    private static RECT shortenIfFullscreen(long handle, HWND w) {
        RECT r = new RECT(), client = new RECT();
        WinUser.MONITORINFO m = new WinUser.MONITORINFO();
        if (!User32.INSTANCE.GetWindowRect(w, r) || !User32.INSTANCE.GetClientRect(w, client)
                || !User32.INSTANCE.GetMonitorInfo(User32.INSTANCE.MonitorFromWindow(w, Win32.MONITOR_DEFAULTTONEAREST), m).booleanValue()) {
            return null;
        }
        RECT mon = m.rcMonitor;
        int[] origin = Win32.clientOrigin(handle);
        if (origin[0] > mon.left || origin[1] > mon.top || origin[0] + client.right < mon.right || origin[1] + client.bottom < mon.bottom) {
            return null;
        }
        User32.INSTANCE.SetWindowPos(w, null, r.left, r.top, r.right - r.left, mon.bottom - r.top - 1, SWP_NOZORDER | SWP_NOACTIVATE);
        return r;
    }

    private static int[] layeredAttributes(HWND w) {
        IntByReference key = new IntByReference(), flags = new IntByReference();
        ByteByReference alpha = new ByteByReference((byte) 255);
        User32.INSTANCE.GetLayeredWindowAttributes(w, key, alpha, flags);
        return new int[] {key.getValue(), alpha.getValue() & 0xFF, flags.getValue()};
    }

    /**
     * Makes the window itself see-through where {@code hole} is, for games whose pictures reach the screen some way
     * that skips the see-through color: with Vulkan, the test PC's NVIDIA driver ignored it (2026-10-02). A window's
     * shape (its region) leaves that part out of the window altogether, however its pictures are shown. The pieces
     * in {@code keep} stay part of the window (hints drawn over the hole). All in pixels of the window's drawing area,
     * {left, top, right, bottom}. Asks Windows only when something changed. On the game's own thread.
     */
    public void shape(int[] hole, List<int[]> keep) {
        if (closed) return;
        RECT frame = new RECT(), visible = new RECT();
        if (!User32.INSTANCE.GetWindowRect(window, frame)) return;
        // Only the part of the window you see: its invisible resize borders reach onto the next monitor when it's
        // maximized, and as part of a region they'd be drawn.
        if (Win32.Dwm.I.DwmGetWindowAttribute(window, DWMWA_EXTENDED_FRAME_BOUNDS, visible, visible.size()) != 0) {
            visible = frame;
        }
        int[] origin = Win32.clientOrigin(handle);
        // A region counts from the window's top-left corner, title bar included; the drawing area starts further in.
        int dx = origin[0] - frame.left, dy = origin[1] - frame.top;
        int[] wanted = new int[10 + 4 * keep.size()];
        wanted[0] = visible.left - frame.left;
        wanted[1] = visible.top - frame.top;
        wanted[2] = visible.right - frame.left;
        wanted[3] = visible.bottom - frame.top;
        wanted[4] = dx;
        wanted[5] = dy;
        System.arraycopy(hole, 0, wanted, 6, 4);
        for (int i = 0; i < keep.size(); i++) System.arraycopy(keep.get(i), 0, wanted, 10 + 4 * i, 4);
        boolean active = window.equals(User32.INSTANCE.GetForegroundWindow());
        long now = System.nanoTime();
        if (!Arrays.equals(wanted, shaped)) {
            HRGN region = GDI32.INSTANCE.CreateRectRgn(wanted[0], wanted[1], wanted[2], wanted[3]);
            combine(region, hole, dx, dy, RGN_DIFF);
            for (int[] piece : keep) combine(region, piece, dx, dy, RGN_OR);
            User32.INSTANCE.SetWindowRgn(window, region, true); // Windows owns the region from here on, and frees it
            shaped = wanted;
            redrawFrame();
        } else if (active != wasActive) {
            redrawFrame();
            redrawAgainAt = now + REDRAW_AGAIN_NANOS; // in case the window hears of the change only later
        } else if (redrawAgainAt != 0 && now >= redrawAgainAt) {
            redrawFrame();
            redrawAgainAt = 0;
        }
        wasActive = active;
    }

    /**
     * A window with a region gets the old-style frame (a white edge and old buttons on a windowed game, seen
     * 2026-10-02), and again each time it gains or loses the focus. Switching Windows' own frame drawing off and on
     * brings the usual one back; turning it on alone does only the first time (probes with windows of their own).
     */
    private void redrawFrame() {
        frameDrawing(DWMNCRP_DISABLED);
        frameDrawing(DWMNCRP_ENABLED);
    }

    private void frameDrawing(int policy) {
        Win32.Dwm.I.DwmSetWindowAttribute(window, DWMWA_NCRENDERING_POLICY, new IntByReference(policy), 4);
    }

    private static void combine(HRGN into, int[] r, int dx, int dy, int mode) {
        HRGN part = GDI32.INSTANCE.CreateRectRgn(r[0] + dx, r[1] + dy, r[2] + dx, r[3] + dy);
        GDI32.INSTANCE.CombineRgn(into, into, part, mode);
        GDI32.INSTANCE.DeleteObject(part);
    }

    /** Whether the window is above normal windows right now. Without that, a clicked app comes in front of it. */
    public boolean isTopmost() {
        return (User32.INSTANCE.GetWindowLong(window, GWL_EXSTYLE) & WS_EX_TOPMOST) != 0;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (shaped != null) { // the whole window again, its frame as Windows decides (the default)
            User32.INSTANCE.SetWindowRgn(window, null, true);
            frameDrawing(DWMNCRP_USEWINDOWSTYLE);
        }
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
