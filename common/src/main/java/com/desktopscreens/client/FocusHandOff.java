package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.core.Win32;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;

/**
 * While another window has focus, Minecraft would pause itself (the "pause on lost focus" option) and,
 * in fullscreen, minimize itself. Both are turned off for as long as a desktop view is open, the settings
 * panel included: clicking a window on another monitor must not minimize the game you're looking at it
 * from. They're put back once the game has focus again, at which point the mouse gets grabbed again too.
 */
final class FocusHandOff {
    private static final long GIVE_UP_MILLIS = 3000;

    private static boolean inDesktopMode, returning;
    private static boolean savedPauseOnLostFocus;
    private static int savedAutoIconify;
    private static long returnedAt;

    private FocusHandOff() {}

    static void begin(Minecraft mc) {
        if (inDesktopMode) return; // already on; saving again would save our own values over the player's
        long window = mc.getWindow().getWindow();
        if (!returning) { // if we're still returning from the last session, the saved values are the real ones
            savedPauseOnLostFocus = mc.options.pauseOnLostFocus;
            savedAutoIconify = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_AUTO_ICONIFY);
        }
        returning = false;
        inDesktopMode = true;
        mc.options.pauseOnLostFocus = false;
        GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_AUTO_ICONIFY, GLFW.GLFW_FALSE);
    }

    /** While a desktop view is open, or the game is on its way back from one: this decides about minimizing then. */
    static boolean active() {
        return inDesktopMode || returning;
    }

    static void end() {
        if (!inDesktopMode) return;
        inDesktopMode = false;
        returning = true;
        returnedAt = Util.getMillis();
    }

    static void tick(Minecraft mc) {
        if (!returning) return;
        boolean focused = mc.isWindowActive();
        // Closing a screen only grabs the mouse if the game already has focus, which can arrive a moment later.
        if (focused && mc.screen == null && !mc.mouseHandler.isMouseGrabbed()) mc.mouseHandler.grabMouse();
        if (focused || Util.getMillis() - returnedAt > GIVE_UP_MILLIS) {
            if (!focused) {
                DesktopScreens.LOG.info("Back from the desktop, but the game hasn't had the focus for {} s; pausing on lost focus again (game window {})",
                        GIVE_UP_MILLIS / 1000, Win32.windowState(GLFWNativeWin32.glfwGetWin32Window(mc.getWindow().getWindow())));
            }
            returning = false;
            mc.options.pauseOnLostFocus = savedPauseOnLostFocus;
            GLFW.glfwSetWindowAttrib(mc.getWindow().getWindow(), GLFW.GLFW_AUTO_ICONIFY, savedAutoIconify);
        }
    }
}
