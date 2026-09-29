package com.desktopscreens.core;

import com.desktopscreens.core.Win32.U32;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * A real app window, for showing just that one instead of its whole monitor (Right Ctrl + W). Only valid while
 * the window lives; {@link #key} names it across restarts by program and window class, never by its title.
 */
public final class AppWindow {
    private static final int GWL_STYLE = -16, GWL_EXSTYLE = -20, WS_CAPTION = 0x00C00000, WS_THICKFRAME = 0x00040000;
    private static final int WS_EX_TOOLWINDOW = 0x00000080, WS_EX_APPWINDOW = 0x00040000;
    private static final int DWMWA_EXTENDED_FRAME_BOUNDS = 9, DWMWA_CLOAKED = 14, SW_RESTORE = 9;
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    /** Shell windows that must never be moved, or shown on their own: taskbars and the desktop itself. */
    static final Set<String> SHELL_CLASSES = new HashSet<String>(Arrays.asList(
            "Shell_TrayWnd", "Shell_SecondaryTrayWnd", "Progman", "WorkerW", "Windows.UI.Core.CoreWindow"));

    final HWND hwnd;
    /** Program file name, like {@code chrome.exe}, and window class. */
    public final String exe, windowClass;

    private AppWindow(HWND hwnd) {
        this.hwnd = hwnd;
        this.exe = exeName(hwnd);
        this.windowClass = className(hwnd);
    }

    /** "program|class", saved in the config to find the window again next time. */
    public String key() {
        return exe + "|" + windowClass;
    }

    /** The title bar text, for the screen only: never log or save it. Falls back to the program name. */
    public String label() {
        char[] text = new char[256];
        String title = User32.INSTANCE.GetWindowText(hwnd, text, text.length) > 0 ? Native.toString(text).trim() : "";
        return title.isEmpty() ? exe : title;
    }

    /** Still open and not hidden. */
    public boolean alive() {
        return User32.INSTANCE.IsWindow(hwnd) && User32.INSTANCE.IsWindowVisible(hwnd);
    }

    /** Whether Windows counts it as being on this monitor (the one it's mostly on). */
    public boolean isOn(Monitor m) {
        return m.handle.equals(User32.INSTANCE.MonitorFromWindow(hwnd, Win32.MONITOR_DEFAULTTONEAREST));
    }

    public long handle() {
        return Pointer.nativeValue(hwnd.getPointer());
    }

    /**
     * The program behind the window, for recording its sound. A store app's window belongs to ApplicationFrameHost,
     * which only draws the frame; the app itself owns the CoreWindow inside it.
     */
    public int processId() {
        final int frame = processOf(hwnd);
        if (!"ApplicationFrameWindow".equals(windowClass)) return frame;
        final int[] app = {frame};
        User32.INSTANCE.EnumChildWindows(hwnd, new WinUser.WNDENUMPROC() {
            @Override
            public boolean callback(HWND w, Pointer data) {
                if (!"Windows.UI.Core.CoreWindow".equals(className(w)) || processOf(w) == frame) return true;
                app[0] = processOf(w);
                return false;
            }
        }, null);
        return app[0];
    }

    private static int processOf(HWND w) {
        IntByReference pid = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(w, pid);
        return pid.getValue();
    }

    /**
     * The part of the window you can see on the monitor, {left, top, right, bottom} in desktop pixels: its frame
     * without the invisible resize borders around it. Null while it's minimized or not on the monitor at all.
     */
    public int[] rectOn(Monitor m) {
        return rectOn(hwnd, m);
    }

    static int[] rectOn(HWND hwnd, Monitor m) {
        if (U32.I.IsIconic(hwnd)) return null;
        RECT r = new RECT();
        if (Win32.Dwm.I.DwmGetWindowAttribute(hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, r, r.size()) != 0
                && !User32.INSTANCE.GetWindowRect(hwnd, r)) {
            return null;
        }
        int left = Math.max(r.left, m.x), top = Math.max(r.top, m.y);
        int right = Math.min(r.right, m.x + m.width), bottom = Math.min(r.bottom, m.y + m.height);
        return right > left && bottom > top ? new int[] {left, top, right, bottom} : null;
    }

    /** Brings it back if it's minimized. Async, so a frozen app can't freeze the game. */
    public void restore() {
        if (U32.I.IsIconic(hwnd)) U32.I.ShowWindowAsync(hwnd, SW_RESTORE);
    }

    public boolean sameAs(AppWindow other) {
        return other != null && hwnd.equals(other.hwnd);
    }

    /** "class (program.exe)", for the log. */
    @Override
    public String toString() {
        return windowClass + " (" + exe + ")";
    }

    /** The app window at a point on the desktop, or null if there's none (the desktop, the taskbar, a menu...). */
    public static AppWindow under(int x, int y) {
        POINT.ByValue p = new POINT.ByValue(x, y);
        HWND w = U32.I.WindowFromPoint(p);
        if (w == null) return null;
        w = U32.I.GetAncestor(w, Win32.GA_ROOT);
        return w != null && isAppWindow(w) && !cloaked(w) ? new AppWindow(w) : null;
    }

    /**
     * The window with this handle if it's still open and still that {@link #key}, else the front-most one with the
     * key. Two windows of one program (two Chrome windows) share a key, so while they're open the handle tells them
     * apart: with the key alone, a window pinned to each screen showed the same one on both (2026-09-26).
     * Handles stay valid while a window lives, also across restarts of the game.
     */
    public static AppWindow find(String key, long handle) {
        if (key == null || key.isEmpty()) return null;
        HWND w = handle == 0 ? null : Win32.hwnd(handle);
        if (w != null && User32.INSTANCE.IsWindow(w) && isAppWindow(w) && !cloaked(w)) {
            AppWindow candidate = new AppWindow(w);
            if (candidate.key().equals(key)) return candidate;
        }
        return find(key);
    }

    /** The front-most app window with this {@link #key}, or null if none is open. */
    public static AppWindow find(final String key) {
        if (key == null || key.isEmpty()) return null;
        final AppWindow[] found = new AppWindow[1];
        User32.INSTANCE.EnumWindows(new WinUser.WNDENUMPROC() { // front to back
            @Override
            public boolean callback(HWND w, Pointer data) {
                if (isAppWindow(w) && !cloaked(w)) {
                    AppWindow candidate = new AppWindow(w);
                    if (candidate.key().equals(key)) {
                        found[0] = candidate;
                        return false;
                    }
                }
                return true;
            }
        }, null);
        return found[0];
    }

    /** Real app windows only: not menus, tooltips, the taskbar, the desktop, or our own windows. */
    static boolean isAppWindow(HWND w) {
        if (!User32.INSTANCE.IsWindowVisible(w) || !w.equals(U32.I.GetAncestor(w, Win32.GA_ROOT))) return false;
        if (isOwnWindow(w)) return false;
        int style = User32.INSTANCE.GetWindowLong(w, GWL_STYLE), exStyle = User32.INSTANCE.GetWindowLong(w, GWL_EXSTYLE);
        if ((exStyle & WS_EX_TOOLWINDOW) != 0) return false;
        if ((style & (WS_CAPTION | WS_THICKFRAME)) == 0 && (exStyle & WS_EX_APPWINDOW) == 0) return false;
        return !SHELL_CLASSES.contains(className(w));
    }

    /** Hidden by Windows while still "visible": store apps that aren't running, windows on other virtual desktops. */
    private static boolean cloaked(HWND w) {
        IntByReference cloaked = new IntByReference();
        return Win32.Dwm.I.DwmGetWindowAttribute(w, DWMWA_CLOAKED, cloaked, 4) == 0 && cloaked.getValue() != 0;
    }

    static boolean isOwnWindow(HWND w) {
        IntByReference pid = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(w, pid);
        return pid.getValue() == Kernel32.INSTANCE.GetCurrentProcessId();
    }

    static String className(HWND w) {
        char[] cls = new char[128];
        User32.INSTANCE.GetClassName(w, cls, cls.length);
        return Native.toString(cls);
    }

    /** The program's file name, like {@code chrome.exe}, or "?" if Windows won't say (an admin program, say). */
    static String exeName(HWND w) {
        IntByReference pid = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(w, pid);
        HANDLE process = Kernel32.INSTANCE.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid.getValue());
        if (process == null) return "?";
        try {
            char[] path = new char[512];
            IntByReference length = new IntByReference(path.length);
            if (!Kernel32.INSTANCE.QueryFullProcessImageName(process, 0, path, length)) return "?";
            String full = new String(path, 0, length.getValue());
            return full.substring(full.lastIndexOf('\\') + 1);
        } finally {
            Kernel32.INSTANCE.CloseHandle(process);
        }
    }
}
