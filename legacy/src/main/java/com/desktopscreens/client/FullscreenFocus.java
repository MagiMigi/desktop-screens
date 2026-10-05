package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.core.Monitor;
import com.desktopscreens.core.Monitors;
import com.desktopscreens.core.Win32;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.win32.StdCallLibrary;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

/**
 * Fullscreen Minecraft minimizes itself whenever another window gets the focus, even one on another monitor: chatting
 * in Discord on the second monitor, the game vanished from the first, and from a stream of it. Now it only does so for
 * a window on its own monitor, which would otherwise stay hidden behind it; for one only on another monitor it stays
 * on screen, as a windowed game would. {@link FocusGuard} keeps LWJGL from minimizing, and this does it instead, every
 * tick. Not while a desktop view is open: {@link FocusHandOff} keeps the game up then, whatever gets the focus. Off
 * with {@link DesktopConfig#fullscreenStays}.
 *
 * <p>Two more things LWJGL 2 does in fullscreen, which the newer versions don't (2026-10-03, Test 22): it keeps the
 * pointer inside the game window even in menus, so another monitor's windows can't be reached with the mouse at all
 * (let go of here, see {@link #releasePointer}); and Alt+Tab, the only way out then, first gives the focus to Windows'
 * own switcher, which sits on the game's monitor, so the game minimized before a window was even picked (now it waits
 * for the window picked: {@link Win32.Front#PICKING} changes nothing).
 */
final class FullscreenFocus {
    private static final int SW_SHOWMINNOACTIVE = 7;
    /** What was in front last tick while the game didn't have the focus, so each change is acted on (and logged) once. */
    private static Win32.Front last = Win32.Front.GAME;
    /**
     * The desktop window in front and whether it was clicked on the game's monitor: decided when it came to the front,
     * by where the mouse was then. Asked again every tick, a click on the desktop of monitor 2 minimized the game once
     * the mouse went back to click the game.
     */
    private static long desktop;
    private static boolean desktopHere;
    private static boolean releasedBefore;

    private interface Cursor extends StdCallLibrary {
        Cursor I = Native.load("user32", Cursor.class);

        boolean ClipCursor(RECT rect);

        boolean GetClipCursor(RECT rect);
    }

    private FullscreenFocus() {}

    static void tick(Minecraft mc) {
        if (!Win32.SUPPORTED) return;
        DesktopConfig.load();
        boolean watching = DesktopConfig.fullscreenStays && FocusGuard.watching() && Display.isFullscreen();
        long hwnd = GameWindow.hwnd();
        if (watching && !FocusHandOff.active()) releasePointer(hwnd);
        if (!watching || FocusHandOff.active() || Display.isActive()) {
            last = Win32.Front.GAME;
            desktop = 0;
            return;
        }
        Monitor monitor = Monitors.of(hwnd);
        if (monitor == null || Win32.minimized(hwnd)) return;
        Win32.Front front = Win32.front(hwnd, monitor);
        if (front == Win32.Front.DESKTOP) {
            long fg = Win32.foregroundWindow();
            if (fg != desktop) {
                desktop = fg;
                int[] c = Win32.cursorPos();
                desktopHere = c == null || monitor.contains(c[0], c[1]);
            }
            front = desktopHere ? Win32.Front.SAME_MONITOR : Win32.Front.OTHER_MONITOR;
        } else {
            desktop = 0;
        }
        if (front == last) return;
        last = front;
        if (front == Win32.Front.SAME_MONITOR) {
            DesktopScreens.LOG.info("Fullscreen: {} got the focus on the game's monitor, so the game minimizes, as Minecraft does", Win32.frontName());
            User32.INSTANCE.ShowWindow(new HWND(new Pointer(hwnd)), SW_SHOWMINNOACTIVE);
        } else if (front == Win32.Front.OTHER_MONITOR) {
            DesktopScreens.LOG.info("Fullscreen: {} got the focus on another monitor, so the game stays on screen", Win32.frontName());
        }
    }

    /**
     * LWJGL 2 keeps the pointer inside a fullscreen game window ({@code ClipCursor}), menus included, whenever the game
     * has the focus; it sets that again on every focus change and every menu opened or closed. While the game doesn't
     * hold the mouse itself (a menu is open: it's grabbed while playing), the pointer is let go again, so it can go to
     * another monitor as with the newer versions. Every tick; never while a desktop view is open (that keeps the
     * pointer on the monitor in use).
     */
    private static void releasePointer(long hwnd) {
        if (Mouse.isGrabbed() || !Display.isActive()) return;
        RECT clip = new RECT(), window = new RECT();
        if (!Cursor.I.GetClipCursor(clip) || !User32.INSTANCE.GetWindowRect(new HWND(new Pointer(hwnd)), window)) return;
        if (clip.left != window.left || clip.top != window.top || clip.right != window.right || clip.bottom != window.bottom) return;
        Cursor.I.ClipCursor(null);
        if (!releasedBefore) {
            releasedBefore = true;
            DesktopScreens.LOG.info("Fullscreen: let the pointer leave the game while a menu is open, so other monitors can be reached");
        }
    }
}
