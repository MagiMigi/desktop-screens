package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.core.Win32;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.Display;

/**
 * While another window has focus, Minecraft would pause itself (the "pause on lost focus" option) and, in fullscreen,
 * minimize itself. Both are off for as long as a desktop view is open: clicking a window on another monitor must not
 * minimize the game you're looking at it from ({@link FocusGuard} asks {@link #active}). Pausing is put back once the
 * game has focus again, at which point the mouse gets grabbed again too.
 */
final class FocusHandOff {
    private static final long GIVE_UP_MILLIS = 3000;

    private static boolean inDesktopMode, returning;
    private static boolean savedPauseOnLostFocus;
    private static long returnedAt;

    private FocusHandOff() {}

    static void begin(Minecraft mc) {
        if (inDesktopMode) return; // already on; saving again would save our own value over the player's
        if (!returning) savedPauseOnLostFocus = mc.gameSettings.pauseOnLostFocus; // still returning: the saved one is real
        returning = false;
        inDesktopMode = true;
        mc.gameSettings.pauseOnLostFocus = false;
    }

    /** While a desktop view is open, or the game is on its way back from one: this decides about minimizing then. */
    static boolean active() {
        return inDesktopMode || returning;
    }

    static void end() {
        if (!inDesktopMode) return;
        inDesktopMode = false;
        returning = true;
        returnedAt = Minecraft.getSystemTime();
    }

    static void tick(Minecraft mc) {
        if (!returning) return;
        boolean focused = Display.isActive();
        // Closing a screen only grabs the mouse if the game already has focus, which can arrive a moment later.
        if (focused && mc.currentScreen == null && !mc.inGameHasFocus) mc.setIngameFocus();
        if (focused || Minecraft.getSystemTime() - returnedAt > GIVE_UP_MILLIS) {
            if (!focused) {
                DesktopScreens.LOG.info("Back from the desktop, but the game hasn't had the focus for {} s; pausing on lost focus again (game window {})",
                        GIVE_UP_MILLIS / 1000, Win32.windowState(GameWindow.hwnd()));
            }
            returning = false;
            mc.gameSettings.pauseOnLostFocus = savedPauseOnLostFocus;
        }
    }
}
