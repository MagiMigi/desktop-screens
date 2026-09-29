package com.desktopscreens.core;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/** Win32 calls that jna-platform lacks (or that are simpler with our own types), and small helpers around them. */
public final class Win32 {
    /** Everything in this package needs Windows. Check this before touching any other class here. */
    public static final boolean SUPPORTED = Platform.isWindows();

    static final int SRCCOPY = 0x00CC0020;
    static final int WDA_NONE = 0, WDA_EXCLUDEFROMCAPTURE = 0x11;
    static final int VK_MENU = 0x12, KEYEVENTF_KEYUP = 0x2;
    static final int PW_RENDERFULLCONTENT = 0x2;
    static final int GA_ROOT = 2, MONITOR_DEFAULTTONEAREST = 2;
    static final int CURSOR_SHOWING = 1, DI_NORMAL = 3;
    private static final int GWL_EXSTYLE = -20, WS_EX_TOPMOST = 0x8, WS_EX_TRANSPARENT = 0x20, WS_EX_LAYERED = 0x80000;
    private static final int MAPVK_VSC_TO_VK_EX = 3;

    private Win32() {}

    /**
     * The virtual key at a place on the keyboard, in the current layout, or 0 if there's none. {@code scancode} is the
     * key's scan code plus 0x100 for extended keys (Right Ctrl is 0x11D), as GLFW reports it on Windows.
     */
    public static int virtualKey(int scancode) {
        int code = (scancode & 0x100) != 0 ? 0xE000 | (scancode & 0xFF) : scancode & 0xFF;
        return User32.INSTANCE.MapVirtualKeyEx(code, MAPVK_VSC_TO_VK_EX, User32.INSTANCE.GetKeyboardLayout(0));
    }

    public interface U32 extends StdCallLibrary {
        U32 I = Native.load("user32", U32.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean SetWindowDisplayAffinity(HWND hwnd, int affinity);
        boolean GetWindowDisplayAffinity(HWND hwnd, IntByReference affinity);
        boolean ClipCursor(RECT rect); // null releases the lock
        boolean GetClipCursor(RECT rect);
        boolean SetCursorPos(int x, int y);
        boolean GetCursorPos(POINT p);
        HWND WindowFromPoint(POINT.ByValue p);
        boolean ClientToScreen(HWND hwnd, POINT p);
        HWND GetAncestor(HWND hwnd, int flags);
        HWND GetForegroundWindow();
        HWND GetShellWindow();
        boolean IsHungAppWindow(HWND hwnd);
        boolean SetForegroundWindow(HWND hwnd);
        boolean BringWindowToTop(HWND hwnd);
        boolean AttachThreadInput(int idAttach, int idAttachTo, boolean attach);
        Pointer GetProp(HWND hwnd, String name);
        void keybd_event(byte vk, byte scan, int flags, Pointer extraInfo);
        short GetAsyncKeyState(int vk);
        boolean PostMessage(HWND hwnd, int msg, WPARAM wParam, LPARAM lParam);
        boolean IsZoomed(HWND hwnd);
        boolean IsIconic(HWND hwnd);
        boolean ShowWindowAsync(HWND hwnd, int cmd);
        boolean GetCursorInfo(CURSORINFO info);
        /** With {@link Win32#PW_RENDERFULLCONTENT}: the window's own picture, also what the graphics card draws, covered or not. */
        boolean PrintWindow(HWND hwnd, HDC dc, int flags);
        boolean GetIconInfo(Pointer icon, ICONINFO info);
        boolean DrawIconEx(HDC hdc, int x, int y, Pointer icon, int cx, int cy, int step, Pointer brush, int flags);
        Pointer SetTimer(HWND hwnd, Pointer id, int elapseMillis, Pointer timerProc);
        boolean KillTimer(HWND hwnd, Pointer id);
    }

    public interface Dwm extends StdCallLibrary {
        Dwm I = Native.load("dwmapi", Dwm.class);

        int DwmGetWindowAttribute(HWND hwnd, int attribute, RECT value, int size);
        int DwmGetWindowAttribute(HWND hwnd, int attribute, IntByReference value, int size);
    }

    public interface G32 extends StdCallLibrary {
        G32 I = Native.load("gdi32", G32.class, W32APIOptions.DEFAULT_OPTIONS);

        HDC CreateCompatibleDC(HDC hdc);
        HBITMAP CreateDIBSection(HDC hdc, WinGDI.BITMAPINFO bmi, int usage, PointerByReference bits, Pointer section, int offset);
        HANDLE SelectObject(HDC hdc, HANDLE obj);
        boolean BitBlt(HDC dst, int x, int y, int w, int h, HDC src, int sx, int sy, int rop);
        boolean DeleteObject(HANDLE obj);
        boolean DeleteDC(HDC hdc);
        boolean GdiFlush();
    }

    @Structure.FieldOrder({"cbSize", "flags", "hCursor", "ptScreenPos"})
    public static class CURSORINFO extends Structure {
        public int cbSize;
        public int flags;
        public Pointer hCursor;
        public POINT ptScreenPos;

        public CURSORINFO() {
            cbSize = size();
        }
    }

    @Structure.FieldOrder({"fIcon", "xHotspot", "yHotspot", "hbmMask", "hbmColor"})
    public static class ICONINFO extends Structure {
        public int fIcon, xHotspot, yHotspot;
        public Pointer hbmMask, hbmColor;
    }

    static HWND hwnd(long handle) {
        return handle == 0 ? null : new HWND(new Pointer(handle));
    }

    /**
     * Hides a window from screen capture (Windows 10 2004+), so capturing the monitor it's on shows
     * what's behind it instead of an endless mirror. Also hides it from OBS display capture and screen
     * sharing, so only turn it on while it's actually needed.
     */
    public static boolean setExcludedFromCapture(long window, boolean excluded) {
        return U32.I.SetWindowDisplayAffinity(hwnd(window), excluded ? WDA_EXCLUDEFROMCAPTURE : WDA_NONE);
    }

    /** Whether the window is hidden from screen capture right now ({@link #setExcludedFromCapture}). */
    public static boolean excludedFromCapture(long window) {
        IntByReference affinity = new IntByReference();
        return U32.I.GetWindowDisplayAffinity(hwnd(window), affinity) && affinity.getValue() == WDA_EXCLUDEFROMCAPTURE;
    }

    /** Where the window's drawing area starts on the desktop, in physical pixels: {x, y}. */
    public static int[] clientOrigin(long window) {
        POINT p = new POINT();
        U32.I.ClientToScreen(hwnd(window), p);
        return new int[] {p.x, p.y};
    }

    /** Where the mouse pointer is on the desktop, in physical pixels: {x, y}. Null when Windows won't say (on the UAC prompt, say). */
    public static int[] cursorPos() {
        POINT p = new POINT();
        return U32.I.GetCursorPos(p) ? new int[] {p.x, p.y} : null;
    }

    /** Whether the window's drawing area covers the whole monitor, like a fullscreen game (not a maximized window). */
    public static boolean coversMonitor(long window, Monitor m) {
        RECT client = new RECT();
        if (!User32.INSTANCE.GetClientRect(hwnd(window), client)) return false;
        int[] origin = clientOrigin(window);
        return origin[0] <= m.x && origin[1] <= m.y
                && origin[0] + client.right >= m.x + m.width && origin[1] + client.bottom >= m.y + m.height;
    }

    /**
     * For the log: where a window is and how it's set up, like "at 0,0 1920x1080, drawing area 1920x1080, topmost,
     * in front, hidden from capture".
     */
    public static String windowState(long window) {
        HWND w = hwnd(window);
        if (w == null || !User32.INSTANCE.IsWindow(w)) return "no window";
        RECT r = new RECT(), c = new RECT();
        User32.INSTANCE.GetWindowRect(w, r);
        User32.INSTANCE.GetClientRect(w, c);
        int ex = User32.INSTANCE.GetWindowLong(w, GWL_EXSTYLE);
        StringBuilder s = new StringBuilder("at ").append(r.left).append(',').append(r.top).append(' ')
                .append(r.right - r.left).append('x').append(r.bottom - r.top)
                .append(", drawing area ").append(c.right).append('x').append(c.bottom);
        if (U32.I.IsIconic(w)) s.append(", minimized");
        if (U32.I.IsZoomed(w)) s.append(", maximized");
        if ((ex & WS_EX_TOPMOST) != 0) s.append(", topmost");
        if ((ex & WS_EX_TRANSPARENT) != 0) s.append(", click-through");
        if ((ex & WS_EX_LAYERED) != 0) s.append(", layered");
        s.append(w.equals(U32.I.GetForegroundWindow()) ? ", in front" : ", not in front");
        IntByReference affinity = new IntByReference();
        if (U32.I.GetWindowDisplayAffinity(w, affinity)) {
            int a = affinity.getValue();
            s.append(a == WDA_EXCLUDEFROMCAPTURE ? ", hidden from capture" : a == WDA_NONE ? ", visible to capture" : ", capture affinity " + a);
        }
        return s.toString();
    }

    /**
     * The window's visible frame on the monitor, {left, top, right, bottom} in desktop pixels; null while it's
     * minimized or elsewhere.
     */
    public static int[] rectOn(long window, Monitor m) {
        return AppWindow.rectOn(hwnd(window), m);
    }

    /** Where the window in front of all others is, seen from a fullscreen game on a monitor ({@link #front}). */
    public enum Front {
        /** The game itself, or none (Windows is between two). */
        GAME,
        /** Another window, at least partly on the game's monitor. */
        SAME_MONITOR,
        /** A window only on other monitors. */
        OTHER_MONITOR,
        /** The desktop itself, which spans every monitor: it's on the one it was clicked on. */
        DESKTOP,
        /** A window that's minimized, so it isn't on any monitor yet. */
        NOWHERE
    }

    public static Front front(long game, Monitor m) {
        HWND fg = U32.I.GetForegroundWindow();
        if (fg == null || fg.equals(hwnd(game))) return Front.GAME;
        String cls = AppWindow.className(fg);
        if (cls.equals("Progman") || cls.equals("WorkerW")) return Front.DESKTOP;
        if (U32.I.IsIconic(fg)) return Front.NOWHERE;
        return AppWindow.rectOn(fg, m) != null ? Front.SAME_MONITOR : Front.OTHER_MONITOR;
    }

    /** The window in front of all others, as a number (0 for none), to tell whether it's still the same one. */
    public static long foregroundWindow() {
        HWND fg = U32.I.GetForegroundWindow();
        return fg == null ? 0 : Pointer.nativeValue(fg.getPointer());
    }

    /** "class (program.exe)" of the window in front, for the log; never its title. */
    public static String frontName() {
        HWND fg = U32.I.GetForegroundWindow();
        return fg == null ? "none" : AppWindow.className(fg) + " (" + AppWindow.exeName(fg) + ")";
    }

    public static boolean minimized(long window) {
        return U32.I.IsIconic(hwnd(window));
    }

    /** Whether any part of the window is on the monitor. */
    public static boolean overlaps(long window, Monitor m) {
        RECT r = new RECT();
        if (!User32.INSTANCE.GetWindowRect(hwnd(window), r)) return false;
        return r.left < m.x + m.width && r.right > m.x && r.top < m.y + m.height && r.bottom > m.y;
    }

    /**
     * SetForegroundWindow, with the usual workarounds for Windows' foreground lock. The first one, attaching our input
     * to the thread of the window in front, is skipped when that's a GLFW window of another program (another
     * Minecraft, say). Attached threads share one active window, and GLFW, whenever it polls its events, looks up its
     * window data through the active window: with ours active, that's a pointer into our process, and reading it
     * crashed a second (dev) Minecraft in glfw.dll on 2026-09-24. The Alt tap below lifts the lock without attaching.
     */
    static void forceForeground(HWND target) {
        if (target == null) return;
        HWND fg = U32.I.GetForegroundWindow();
        IntByReference fgProcess = new IntByReference();
        int fgThread = fg == null ? 0 : User32.INSTANCE.GetWindowThreadProcessId(fg, fgProcess);
        int me = Kernel32.INSTANCE.GetCurrentThreadId();
        boolean attached = fgThread != 0 && fgThread != me
                && (fgProcess.getValue() == Kernel32.INSTANCE.GetCurrentProcessId() || !isGlfwWindow(fg))
                && U32.I.AttachThreadInput(me, fgThread, true);
        U32.I.BringWindowToTop(target);
        U32.I.SetForegroundWindow(target);
        if (attached) U32.I.AttachThreadInput(me, fgThread, false);
        if (!target.equals(U32.I.GetForegroundWindow())) {
            // Windows' foreground lock refused us. A synthetic Alt tap lifts it (classic workaround).
            U32.I.keybd_event((byte) VK_MENU, (byte) 0, 0, null);
            U32.I.keybd_event((byte) VK_MENU, (byte) 0, KEYEVENTF_KEYUP, null);
            U32.I.SetForegroundWindow(target);
        }
    }

    /** GLFW keeps a pointer to its own data for each of its windows in the window property "GLFW". */
    private static boolean isGlfwWindow(HWND w) {
        return U32.I.GetProp(w, "GLFW") != null;
    }
}
