package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.core.Monitor;
import com.desktopscreens.core.Monitors;
import com.desktopscreens.core.Win32;
import net.minecraft.client.Minecraft;
//? if <26.3 {
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
//?}

//? if >=26.3 {
/*/^*
 * Nothing to do from 26.3 on: Minecraft no longer minimizes itself when another window gets the focus, and its F11 is
 * a borderless window, which a window clicked on its own monitor simply comes in front of.
 ^/
final class FullscreenFocus {
    private FullscreenFocus() {}

    static void tick(Minecraft mc) {}
}
*///?} else {
/**
 * Fullscreen Minecraft minimizes itself whenever another window gets the focus, even one on another monitor: chatting
 * in Discord on the second monitor, the game vanished from the first, and from a stream of it (2026-09-28).
 * Now it only does so for a window on its own monitor, which would otherwise stay hidden behind it; for one only on
 * another monitor it stays on screen, as a windowed game would. GLFW's own minimizing (GLFW_AUTO_ICONIFY) is off, and
 * this does it instead, every tick. Not while a desktop view is open: {@link FocusHandOff} keeps the game up then,
 * whatever gets the focus. Off with {@link DesktopConfig#fullscreenStays}. Alt+Tab first gives the focus to Windows'
 * own switcher, on the main monitor; that changes nothing ({@link Win32.Front#PICKING}), the window picked decides
 * (2026-10-03: Alt+Tab to a window on monitor 2 minimized the game before). And a window Windows picks by itself, after
 * the one in front was minimized or closed, isn't the player's choice: the game stays and takes the focus back
 * (2026-11-05: minimizing an app on monitor 2 handed the focus to a terminal behind the game, which minimized).
 */
final class FullscreenFocus {
    /** What was in front last tick while the game didn't have the focus, so each change is acted on (and logged) once. */
    private static Win32.Front last = Win32.Front.GAME;
    /** The other window that had the focus before the one in front now (0: the game, or none yet). */
    private static long before, now;
    /**
     * The desktop window in front and whether it was clicked on the game's monitor: decided when it came to the front,
     * by where the mouse was then. Asked again every tick, a click on the desktop of monitor 2 minimized the game once
     * the mouse went back to click the game (2026-09-28).
     */
    private static long desktop;
    private static boolean desktopHere;

    private FullscreenFocus() {}

    static void tick(Minecraft mc) {
        if (!Win32.SUPPORTED) return;
        DesktopConfig.load();
        long window = mc.getWindow().getWindow();
        boolean stays = DesktopConfig.fullscreenStays;
        if (!FocusHandOff.active()) {
            // Checked every tick rather than set once: FocusHandOff puts back whatever it found when a view opened.
            int wanted = stays ? GLFW.GLFW_FALSE : GLFW.GLFW_TRUE;
            if (GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_AUTO_ICONIFY) != wanted) GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_AUTO_ICONIFY, wanted);
        }
        if (!stays || FocusHandOff.active() || !mc.getWindow().isFullscreen() || mc.isWindowActive()) {
            last = Win32.Front.GAME;
            desktop = 0;
            before = now = 0;
            return;
        }
        long hwnd = GLFWNativeWin32.glfwGetWin32Window(window);
        Monitor monitor = Monitors.of(hwnd);
        if (monitor == null || Win32.minimized(hwnd)) return;
        long fg = Win32.foregroundWindow();
        Win32.Front front = Win32.front(hwnd, monitor);
        // Windows' own switcher only passes the focus on (Alt+Tab): not a window the player was in.
        if (fg != now && fg != 0 && fg != hwnd && front != Win32.Front.PICKING) {
            before = now;
            now = fg;
        }
        if (front == Win32.Front.DESKTOP) {
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
        if (front == Win32.Front.SAME_MONITOR && before != 0 && (Win32.minimized(before) || !Win32.exists(before))) {
            // Windows picked it, hidden behind the game: the player only put away the window they were in.
            DesktopScreens.LOG.info("Fullscreen: the window in front was minimized or closed, and Windows gave the focus to {} behind the game, so the game takes it back",
                    Win32.frontName());
            Win32.focus(hwnd);
            last = Win32.Front.GAME;
            before = now = 0;
        } else if (front == Win32.Front.SAME_MONITOR) {
            DesktopScreens.LOG.info("Fullscreen: {} got the focus on the game's monitor, so the game minimizes, as Minecraft does", Win32.frontName());
            GLFW.glfwIconifyWindow(window);
        } else if (front == Win32.Front.OTHER_MONITOR) {
            DesktopScreens.LOG.info("Fullscreen: {} got the focus on another monitor, so the game stays on screen", Win32.frontName());
        }
    }
}
//?}
