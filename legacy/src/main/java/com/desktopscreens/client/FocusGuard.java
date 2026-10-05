package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.core.Win32;
import com.sun.jna.CallbackReference;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.Display;

import java.lang.reflect.Field;

/**
 * Minecraft 1.12.2's window (LWJGL 2) minimizes itself in fullscreen the moment it loses the keyboard focus, to any
 * window anywhere: using monitor 2 from the desktop view would make the game vanish from monitor 1. The newer versions
 * switch that off in GLFW ({@code GLFW_AUTO_ICONIFY}); LWJGL 2 has no such switch. So this sits in front of LWJGL's
 * window procedure, and when the game should stay up (a desktop view is open, see {@link FocusHandOff}, or
 * {@link FullscreenFocus} decides), it lets LWJGL handle the lost focus as if the game weren't fullscreen: everything
 * else LWJGL does then (letting go of held keys, counting itself inactive) still happens. Main thread (the window's
 * messages arrive there, inside Minecraft's {@code Display.update}).
 */
final class FocusGuard {
    private static final int WM_KILLFOCUS = 0x0008, GWLP_WNDPROC = -4;

    /** The window the guard is in front of, and LWJGL's own procedure for it. */
    private static long window;
    private static Pointer lwjgl;
    private static WinUser.WindowProc proc;
    private static Field fullscreen;
    private static boolean failed;

    private FocusGuard() {}

    /** Every tick: switching to or from fullscreen (F11) makes a new window, which needs the guard too. */
    static void tick(Minecraft mc) {
        if (failed || !Win32.SUPPORTED) return;
        long hwnd = GameWindow.hwnd();
        if (hwnd == 0 || hwnd == window) return;
        try {
            if (proc == null) {
                fullscreen = Display.class.getDeclaredField("fullscreen");
                fullscreen.setAccessible(true);
                proc = new WinUser.WindowProc() {
                    @Override
                    public LRESULT callback(HWND w, int message, WPARAM wParam, LPARAM lParam) {
                        return handle(w, message, wParam, lParam);
                    }
                };
            }
            HWND w = new HWND(new Pointer(hwnd));
            Pointer old = User32.INSTANCE.SetWindowLongPtr(w, GWLP_WNDPROC, CallbackReference.getFunctionPointer(proc));
            if (old == null) throw new IllegalStateException("Windows didn't take it (error " + com.sun.jna.Native.getLastError() + ")");
            lwjgl = old;
            window = hwnd;
            DesktopScreens.LOG.info("Watching the game window's focus messages, so fullscreen can stay up when another window gets the focus");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true;
            DesktopScreens.LOG.warn("Couldn't watch the game window's focus messages: fullscreen Minecraft minimizes whenever another window gets the focus", e);
        }
    }

    private static LRESULT handle(HWND w, int message, WPARAM wParam, LPARAM lParam) {
        if (message == WM_KILLFOCUS && Display.isFullscreen() && (FocusHandOff.active() || DesktopConfig.fullscreenStays)) {
            try {
                fullscreen.setBoolean(null, false);
                try {
                    return User32.INSTANCE.CallWindowProc(lwjgl, w, message, wParam, lParam);
                } finally {
                    fullscreen.setBoolean(null, true);
                }
            } catch (IllegalAccessException e) {
                // setAccessible above makes this impossible; let LWJGL handle it as it would
            }
        }
        return User32.INSTANCE.CallWindowProc(lwjgl, w, message, wParam, lParam);
    }

    /** True once the guard is in front of the game's window: the game minimizes only when this says so. */
    static boolean watching() {
        return window != 0 && window == GameWindow.hwnd();
    }
}
