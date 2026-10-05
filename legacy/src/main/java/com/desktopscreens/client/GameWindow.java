package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import org.lwjgl.opengl.Display;

import java.lang.reflect.Method;

/** The game's window as Windows knows it. On 1.12.2, LWJGL 2's Display opened it. */
final class GameWindow {
    private static Method implementation, hwnd;
    private static boolean failed;

    private GameWindow() {}

    /**
     * Its Windows handle (HWND), or 0 if it can't be found. LWJGL 2 keeps it to itself: Display's implementation on
     * Windows (WindowsDisplay) has it, behind methods that aren't public. Asked every time, since switching to and from
     * fullscreen (F11) makes a new window.
     */
    static long hwnd() {
        if (failed) return 0;
        try {
            if (implementation == null) {
                implementation = Display.class.getDeclaredMethod("getImplementation");
                implementation.setAccessible(true);
            }
            Object display = implementation.invoke(null);
            if (hwnd == null) {
                hwnd = display.getClass().getDeclaredMethod("getHwnd");
                hwnd.setAccessible(true);
            }
            return (Long) hwnd.invoke(display);
        } catch (ReflectiveOperationException | RuntimeException e) {
            failed = true;
            DesktopScreens.LOG.warn("Couldn't find the game's window", e);
            return 0;
        }
    }
}
