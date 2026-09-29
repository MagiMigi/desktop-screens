package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.core.Monitor;
import com.desktopscreens.core.Monitors;
import com.desktopscreens.core.Win32;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;

/**
 * Fullscreen Minecraft minimizes itself whenever another window gets the focus, even one on another monitor: chatting
 * in Discord on the second monitor, the game vanished from the first, and from a stream of it (2026-09-28).
 * Now it only does so for a window on its own monitor, which would otherwise stay hidden behind it; for one only on
 * another monitor it stays on screen, as a windowed game would. GLFW's own minimizing (GLFW_AUTO_ICONIFY) is off, and
 * this does it instead, every tick. Not while a desktop view is open: {@link FocusHandOff} keeps the game up then,
 * whatever gets the focus. Off with {@link DesktopConfig#fullscreenStays}.
 */
final class FullscreenFocus {
    /** What was in front last tick while the game didn't have the focus, so each change is acted on (and logged) once. */
    private static Win32.Front last = Win32.Front.GAME;
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
            return;
        }
        long hwnd = GLFWNativeWin32.glfwGetWin32Window(window);
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
            GLFW.glfwIconifyWindow(window);
        } else if (front == Win32.Front.OTHER_MONITOR) {
            DesktopScreens.LOG.info("Fullscreen: {} got the focus on another monitor, so the game stays on screen", Win32.frontName());
        }
    }
}
