package com.desktopscreens.core;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinUser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A fullscreen game's window a pixel bigger than its monitor. One that covers its monitor exactly gets shown directly,
 * and when the see-through view starts or ends, or the focus moves away from it, Windows moves it to its compositor and
 * back, showing an old copy of the picture for a moment (Tests 71-72). Painting the current picture or timing the change
 * didn't help (Test 89). A window a pixel bigger is always composed, so nothing ever switches (Test 90). Minecraft's own
 * F11 does the same from 26.1 on, unless Exclusive Fullscreen is on.
 */
public final class SoftFullscreen {
    private static final int SWP_NOZORDER = 0x4, SWP_NOACTIVATE = 0x10;

    private SoftFullscreen() {}

    /**
     * Makes the window a pixel bigger if its drawing area covers its monitor exactly. On the game's own thread, as it
     * resizes the game. {@code preferRight}: see {@link #grown}. Returns what it did, for the log; null if it did nothing.
     */
    public static String keep(long window, boolean preferRight) {
        HWND w = Win32.hwnd(window);
        RECT frame = new RECT(), client = new RECT();
        WinUser.MONITORINFO info = new WinUser.MONITORINFO();
        if (!User32.INSTANCE.GetWindowRect(w, frame) || !User32.INSTANCE.GetClientRect(w, client)
                || !User32.INSTANCE.GetMonitorInfo(User32.INSTANCE.MonitorFromWindow(w, Win32.MONITOR_DEFAULTTONEAREST), info).booleanValue()) {
            return null;
        }
        RECT m = info.rcMonitor;
        int[] monitor = {m.left, m.top, m.right, m.bottom}, origin = Win32.clientOrigin(window);
        int[] area = {origin[0], origin[1], origin[0] + client.right, origin[1] + client.bottom};
        if (!Arrays.equals(area, monitor)) return null; // the usual case, every tick: nothing more to ask Windows
        List<int[]> others = new ArrayList<int[]>();
        for (Monitor other : Monitors.list()) {
            int[] r = {other.x, other.y, other.x + other.width, other.y + other.height};
            if (!Arrays.equals(r, monitor)) others.add(r);
        }
        int[] to = grown(area, monitor, others, preferRight);
        int width = frame.right - frame.left + to[2] - monitor[2], height = frame.bottom - frame.top + to[3] - monitor[3];
        boolean done = User32.INSTANCE.SetWindowPos(w, null, frame.left, frame.top, width, height, SWP_NOZORDER | SWP_NOACTIVATE);
        return (done ? "window made " : "Windows refused to make the window ") + (to[2] - to[0]) + "x" + (to[3] - to[1]);
    }

    /**
     * The drawing area a pixel bigger, {left, top, right, bottom}, or null unless it's exactly the monitor's. Only down or
     * right, so where the picture starts stays the same. A monitor touching that edge would show the extra row as a thin
     * line, so the other side if that one's free; with both taken, the preferred one. Down is preferred where the game
     * draws on all of it. {@code preferRight}: for 1.12.2, which goes on drawing its old size from the bottom left, so a
     * row added below would leave the row at the top of the monitor undrawn, and a column added to the right is off the
     * monitor anyway.
     */
    static int[] grown(int[] area, int[] monitor, List<int[]> others, boolean preferRight) {
        if (!Arrays.equals(area, monitor)) return null;
        boolean below = false, right = false;
        for (int[] other : others) {
            if (other[1] == monitor[3] && other[0] < monitor[2] && other[2] > monitor[0]) below = true;
            if (other[0] == monitor[2] && other[1] < monitor[3] && other[3] > monitor[1]) right = true;
        }
        boolean growRight = preferRight ? !right || below : below && !right;
        return new int[] {monitor[0], monitor[1], monitor[2] + (growRight ? 1 : 0), monitor[3] + (growRight ? 0 : 1)};
    }
}
