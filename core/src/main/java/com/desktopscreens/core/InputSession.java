package com.desktopscreens.core;

import com.desktopscreens.core.Win32.U32;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.ShellAPI;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinUser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One stretch of desktop mode. It owns the global hooks, which only exist while it's open: a
 * low-level keyboard hook runs inside our process, so leaving it installed would make every key
 * press on the PC wait whenever the JVM pauses for garbage collection.
 */
final class InputSession {
    private static final int WM_QUIT = 0x0012, WM_TIMER = 0x0113, WM_MOUSEWHEEL = 0x020A, WH_MOUSE_LL = 14;
    /** Our own thread messages: the host key went down or up, so the wheel hook comes or goes. */
    private static final int WM_HOST_DOWN = 0x8001, WM_HOST_UP = 0x8002;
    private static final int EVENT_SYSTEM_FOREGROUND = 0x0003, EVENT_SYSTEM_MINIMIZESTART = 0x0016, EVENT_SYSTEM_MINIMIZEEND = 0x0017;
    private static final int EVENT_SYSTEM_MOVESIZESTART = 0x000A, EVENT_SYSTEM_MOVESIZEEND = 0x000B, VK_LBUTTON = 0x01, VK_RBUTTON = 0x02;
    private static final int EVENT_OBJECT_SHOW = 0x8002, GA_ROOTOWNER = 3, ABM_GETSTATE = 4, ABS_AUTOHIDE = 1;
    /** A focus change this soon after something was minimized is Windows picking the next window. */
    private static final long JUST_MINIMIZED_NANOS = 300000000L;
    /** A focus change this soon after a taskbar click is you picking a window. */
    private static final long SWITCHED_NANOS = 1500000000L;
    /** How long after a taskbar click to help if nothing came forward yet, and how long before checking again. */
    private static final int NUDGE_MILLIS = 150, RENUDGE_MILLIS = 300, NUDGES = 2;
    /**
     * How long a window Windows picked on another monitor keeps the focus before the keyboard goes to the desktop.
     * Taken at once, a chat app (on the other monitor) blinked in the taskbar after each minimize here
     * (2026-09-25). Most likely: Chromium-based apps take the focus themselves while handling their activation, and
     * Windows blinks the taskbar button of a window whose SetForegroundWindow is refused. Not proven.
     */
    private static final int SETTLE_MILLIS = 300;
    private static final int WM_NULL = 0x0000, LLKHF_EXTENDED = 0x01;
    /** The taskbars and their thumbnails: clicks there should bring a window forward. */
    private static final Set<String> TASKBAR_CLASSES = new HashSet<String>(Arrays.asList(
            "Shell_TrayWnd", "Shell_SecondaryTrayWnd", "TaskListThumbnailWnd"));
    /** How far the mouse is kept from an auto-hide taskbar's edge while a fullscreen window is in front. */
    private static final int TASKBAR_GAP = 2;
    private static final int WINEVENT_OUTOFCONTEXT = 0, OBJID_WINDOW = 0, CHILDID_SELF = 0;
    private static final int GWL_STYLE = -16, WS_CAPTION = 0x00C00000;
    private static final int SWP_NOZORDER = 0x0004, SWP_NOACTIVATE = 0x0010, SWP_ASYNCWINDOWPOS = 0x4000;
    private static final int SW_SHOWMAXIMIZED = 3, MONITOR_DEFAULTTOPRIMARY = 1;
    /** Backup re-lock check, for anything the WinEvent hooks miss. */
    private static final int RELOCK_CHECK_MILLIS = 15;
    /** The desktop itself. */
    private static final Set<String> DESKTOP_CLASSES = new HashSet<String>(Arrays.asList("Progman", "WorkerW"));
    /** Shell windows you pick another window with: taskbars and their thumbnails, Start and search, Alt+Tab, the tray. */
    private static final Set<String> SWITCHER_CLASSES = new HashSet<String>(Arrays.asList(
            "Shell_TrayWnd", "Shell_SecondaryTrayWnd", "TaskListThumbnailWnd", "Windows.UI.Core.CoreWindow",
            "XamlExplorerHostIslandWindow", "MultitaskingViewFrame", "ForegroundStaging",
            "NotifyIconOverflowWindow", "TopLevelWindowForOverflowXamlIsland"));
    private static final AtomicBoolean shutdownHookAdded = new AtomicBoolean();

    private final Monitor target;
    private final RECT lockRect;
    /** Where the mouse is kept while a fullscreen window is in front: off the auto-hide taskbar's edge. Null if there is none. */
    private final RECT fullscreenLockRect;
    /**
     * While showing just one window: that window, or null for the whole monitor. The mouse stays inside its frame,
     * and windows that come to you are put over it. The frame is read fresh every time, never handed over from
     * the game's frames: after a resize, a stale frame for even one game frame left room to click outside it,
     * onto a window behind it, which then came in front of it.
     */
    private volatile HWND shown;
    /**
     * While a window is being moved or resized, the mouse may leave the window shown: kept inside it, you could
     * shrink it but never make it bigger, since the mouse couldn't pass its edge. No clicks can happen meanwhile,
     * as the button is held down, and the end of it re-locks right away.
     */
    private volatile boolean movingOrSizing;
    /** The game's own window: switching to it yourself means "back to the game". */
    private final HWND game;
    /** The host key: its place on the keyboard (see {@link DesktopInput#setHostKey}), and its virtual key in this layout. */
    private final int hostScancode, hostVirtualKey;
    private final DesktopInput.Listener listener;
    private final CountDownLatch started = new CountDownLatch(1);
    private volatile boolean active = true;
    volatile int moved, moveFailed; // written only by the mover thread
    private volatile int threadId;
    /**
     * Moving windows and changing the focus can wait on the app involved, so they run here and never on the
     * hook thread, whose keyboard hook the whole PC's keyboard waits on.
     */
    private final ScheduledExecutorService mover = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "Desktop Screens window mover");
            t.setDaemon(true);
            return t;
        }
    });

    // Only touched by the hook thread. The callbacks are fields so the GC can't free them while Windows still calls them.
    private WinUser.HHOOK keyHook, wheelHook;
    private HANDLE systemEventHook, showEventHook;
    private WinUser.LowLevelKeyboardProc keyProc;
    private WinUser.LowLevelMouseProc wheelProc;
    private WinUser.WinEventProc winEventProc;
    private boolean hostDown, comboUsed;
    private final boolean[] swallowedKeys = new boolean[256]; // keys whose key-down we ate, so we eat their key-up too
    /** The last window that had the focus, not counting the switchers you pick windows with. */
    private volatile HWND lastFocused;
    private long lastMinimizedAt = -JUST_MINIMIZED_NANOS;
    /** The taskbar had the focus, and no window has had it since. Hook thread writes, mover reads. */
    private volatile boolean switcherPending;
    private volatile long lastSwitcherAt;

    InputSession(Monitor target, AppWindow shown, HWND game, int hostScancode, DesktopInput.Listener listener) {
        this.target = target;
        this.lockRect = target.rect();
        this.fullscreenLockRect = awayFromAutoHideTaskbar(target, lockRect);
        this.shown = shown == null ? null : shown.hwnd;
        this.game = game;
        this.hostScancode = hostScancode;
        this.hostVirtualKey = Win32.virtualKey(hostScancode);
        this.listener = listener;
        if (shutdownHookAdded.compareAndSet(false, true)) {
            // If the game quits or crashes mid-session, never leave the mouse locked.
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    U32.I.ClipCursor(null);
                }
            }, "Desktop Screens mouse unlock"));
        }
        U32.I.ClipCursor(lockWanted());
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                hookLoop();
            }
        }, "Desktop Screens input hooks");
        thread.setDaemon(true);
        thread.start();
        try {
            started.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Keeps the mouse inside this window from now on, or the whole monitor again with null. Any thread. */
    void show(AppWindow window) {
        shown = window == null ? null : window.hwnd;
        relock();
    }

    /** The part of the monitor the window shown covers right now, or null: none shown, or it's minimized. */
    private RECT area() {
        HWND w = shown;
        int[] r = w == null ? null : AppWindow.rectOn(w, target);
        if (r == null) return null;
        RECT out = new RECT();
        out.left = r[0];
        out.top = r[1];
        out.right = r[2];
        out.bottom = r[3];
        return out;
    }

    void close() {
        synchronized (this) { // so a relock racing with this can't lock the mouse again after we let it go
            active = false;
            U32.I.ClipCursor(null);
        }
        mover.shutdown();
        int id = threadId;
        if (id != 0) User32.INSTANCE.PostThreadMessage(id, WM_QUIT, null, null);
    }

    private void hookLoop() {
        threadId = Kernel32.INSTANCE.GetCurrentThreadId();
        keyProc = new WinUser.LowLevelKeyboardProc() {
            @Override
            public LRESULT callback(int nCode, WinUser.WPARAM wParam, WinUser.KBDLLHOOKSTRUCT info) {
                if (nCode >= 0 && handleKey(info.vkCode & 0xFF, isHostKey(info), wParam.intValue())) {
                    return new LRESULT(1); // swallowed: apps never see it
                }
                return User32.INSTANCE.CallNextHookEx(keyHook, nCode, wParam, new LPARAM(Pointer.nativeValue(info.getPointer())));
            }
        };
        wheelProc = new WinUser.LowLevelMouseProc() {
            @Override
            public LRESULT callback(int nCode, WinUser.WPARAM wParam, WinUser.MSLLHOOKSTRUCT info) {
                if (nCode >= 0 && wParam.intValue() == WM_MOUSEWHEEL && hostDown) {
                    comboUsed = true;
                    if (active) listener.onHostWheel((short) (info.mouseData >>> 16));
                    return new LRESULT(1); // swallowed: the app under the mouse doesn't scroll
                }
                return User32.INSTANCE.CallNextHookEx(wheelHook, nCode, wParam, new LPARAM(Pointer.nativeValue(info.getPointer())));
            }
        };
        winEventProc = new WinUser.WinEventProc() {
            @Override
            public void callback(HANDLE hook, WinUser.DWORD event, WinUser.HWND hwnd, WinUser.LONG idObject,
                                 WinUser.LONG idChild, WinUser.DWORD thread, WinUser.DWORD time) {
                int e = event.intValue();
                if (e == EVENT_SYSTEM_MOVESIZESTART) {
                    movingOrSizing = true;
                } else if (e == EVENT_SYSTEM_MOVESIZEEND) {
                    movingOrSizing = false; // the relock below narrows it to the window's new frame right away
                }
                relock();
                if (hwnd == null || idObject.intValue() != OBJID_WINDOW || idChild.intValue() != CHILDID_SELF) return;
                // New windows, and windows you restore from the taskbar, come to you.
                if (e == EVENT_OBJECT_SHOW) bringOver(hwnd, "appeared");
                else if (e == EVENT_SYSTEM_MINIMIZEEND) bringOver(hwnd, "restored");
                else if (e == EVENT_SYSTEM_MINIMIZESTART) lastMinimizedAt = System.nanoTime();
                else if (e == EVENT_SYSTEM_FOREGROUND) focusChanged(hwnd);
            }
        };
        lastFocused = U32.I.GetForegroundWindow();
        keyHook = User32.INSTANCE.SetWindowsHookEx(WinUser.WH_KEYBOARD_LL, keyProc, Kernel32.INSTANCE.GetModuleHandle(null), 0);
        // Foreground changes, menus, mouse capture, move/size, minimize: everything that can make Windows drop the lock.
        systemEventHook = User32.INSTANCE.SetWinEventHook(EVENT_SYSTEM_FOREGROUND, EVENT_SYSTEM_MINIMIZEEND, null, winEventProc, 0, 0, WINEVENT_OUTOFCONTEXT);
        // Windows appearing, so new ones get moved over before they show up on the wrong monitor.
        showEventHook = User32.INSTANCE.SetWinEventHook(EVENT_OBJECT_SHOW, EVENT_OBJECT_SHOW, null, winEventProc, 0, 0, WINEVENT_OUTOFCONTEXT);
        Pointer timer = U32.I.SetTimer(null, Pointer.NULL, RELOCK_CHECK_MILLIS, null);
        started.countDown();

        WinUser.MSG msg = new WinUser.MSG();
        while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
            // The hooks are called from inside GetMessage. Thread timers arrive as plain messages.
            if (msg.message == WM_TIMER) relock();
            else if (msg.message == WM_HOST_DOWN) hookWheel(true);
            else if (msg.message == WM_HOST_UP) hookWheel(false);
        }

        hookWheel(false);
        U32.I.KillTimer(null, timer);
        User32.INSTANCE.UnhookWinEvent(showEventHook);
        User32.INSTANCE.UnhookWinEvent(systemEventHook);
        User32.INSTANCE.UnhookWindowsHookEx(keyHook);
    }

    /**
     * Host key logic, VirtualBox style: tapping the host key alone means "back to the game" (on release,
     * so it can't be confused with a combo), and any key pressed while it's held is a command. Returns
     * whether to swallow the event. Runs on the hook thread, which Windows makes the whole PC's keyboard
     * wait on, so it must stay quick.
     */
    private boolean handleKey(int vk, boolean host, int msg) {
        boolean isDown = msg == WinUser.WM_KEYDOWN || msg == WinUser.WM_SYSKEYDOWN;
        if (host) {
            if (isDown) {
                if (!hostDown && active) {
                    hostDown = true;
                    comboUsed = false;
                    User32.INSTANCE.PostThreadMessage(threadId, WM_HOST_DOWN, null, null);
                }
                return hostDown; // includes auto-repeat
            }
            if (!hostDown) return false;
            hostDown = false;
            User32.INSTANCE.PostThreadMessage(threadId, WM_HOST_UP, null, null);
            if (!comboUsed && active) listener.onHostKey();
            return true;
        }
        if (isDown && hostDown) {
            // Everything pressed while the host key is held is ours. Apps would otherwise get e.g. a bare "s".
            if (!swallowedKeys[vk]) { // not an auto-repeat
                comboUsed = true;
                if (active) listener.onHostCombo(vk);
            }
            swallowedKeys[vk] = true;
            return true;
        }
        if (!isDown && swallowedKeys[vk]) {
            swallowedKeys[vk] = false;
            return true;
        }
        return false;
    }

    /**
     * Whether a key is the host key: by its place on the keyboard, so the numpad's Enter is never the main one. A key a
     * program sent without a scan code (0) is judged by its virtual key instead.
     */
    private boolean isHostKey(WinUser.KBDLLHOOKSTRUCT info) {
        if (info.scanCode == 0) return (info.vkCode & 0xFF) == hostVirtualKey;
        return (info.scanCode | ((info.flags & LLKHF_EXTENDED) != 0 ? 0x100 : 0)) == hostScancode;
    }

    /**
     * The mouse hook that turns the wheel into zoom only exists while the host key is held. Every mouse move on the
     * PC goes through a low-level mouse hook, so it would stutter whenever the JVM pauses for garbage collection.
     * Hook thread only.
     */
    private void hookWheel(boolean on) {
        if (on && wheelHook == null) {
            wheelHook = User32.INSTANCE.SetWindowsHookEx(WH_MOUSE_LL, wheelProc, Kernel32.INSTANCE.GetModuleHandle(null), 0);
        } else if (!on && wheelHook != null) {
            User32.INSTANCE.UnhookWindowsHookEx(wheelHook);
            wheelHook = null;
        }
    }

    /**
     * Windows drops the mouse lock whenever a window is activated, moved or resized. Put it back, and
     * pull the mouse back in if it slipped out in between (ClipCursor does that on its own).
     */
    private synchronized void relock() {
        if (!active) return;
        // In case the end of a move or resize never comes (the window closed halfway): it's over once no button is held.
        // Physical buttons, so both, in case they're swapped for left-handed use.
        if (movingOrSizing && (U32.I.GetAsyncKeyState(VK_LBUTTON) & 0x8000) == 0 && (U32.I.GetAsyncKeyState(VK_RBUTTON) & 0x8000) == 0) {
            movingOrSizing = false;
        }
        RECT want = lockWanted();
        RECT current = new RECT();
        POINT p = new POINT();
        boolean lost = U32.I.GetClipCursor(current) && (current.left != want.left || current.top != want.top
                || current.right != want.right || current.bottom != want.bottom);
        boolean out = U32.I.GetCursorPos(p) && (p.x < want.left || p.y < want.top || p.x >= want.right || p.y >= want.bottom);
        if (lost || out) U32.I.ClipCursor(want);
    }

    /**
     * The monitor (off an auto-hide taskbar's edge while something fullscreen is in front), narrowed to the window
     * shown, except while a window is being moved or resized.
     */
    private RECT lockWanted() {
        RECT base = fullscreenLockRect != null && fullscreenInFront() ? fullscreenLockRect : lockRect;
        if (movingOrSizing) return base;
        RECT a = area();
        if (a == null) return base;
        RECT r = new RECT();
        r.left = Math.max(base.left, a.left);
        r.top = Math.max(base.top, a.top);
        r.right = Math.min(base.right, a.right);
        r.bottom = Math.min(base.bottom, a.bottom);
        return r.right > r.left && r.bottom > r.top ? r : base;
    }

    /**
     * Whether the window in front is fullscreen on this monitor, like a video: covering all of it without a title
     * bar. It may still count as maximized (a video made fullscreen in a maximized browser does). Plain maximized
     * windows keep their title bar style, even the ones that draw their own like Chrome and Discord, and over those
     * the taskbar should come up. The desktop covers every monitor, so shell windows never count.
     */
    private boolean fullscreenInFront() {
        HWND w = U32.I.GetForegroundWindow();
        if (w == null || (User32.INSTANCE.GetWindowLong(w, GWL_STYLE) & WS_CAPTION) == WS_CAPTION) return false;
        RECT r = new RECT();
        if (!User32.INSTANCE.GetWindowRect(w, r) || r.left > lockRect.left || r.top > lockRect.top
                || r.right < lockRect.right || r.bottom < lockRect.bottom) {
            return false;
        }
        String cls = className(w);
        return !isOwnWindow(w) && !AppWindow.SHELL_CLASSES.contains(cls) && !SWITCHER_CLASSES.contains(cls);
    }

    /**
     * Over a fullscreen window (a video, say), an auto-hide taskbar on the game's monitor stays down, but on
     * other monitors Windows still brings it up at the touch of the mouse. So while one is in front, keep the
     * mouse a little away from that edge. Returns that smaller rectangle, or null without an auto-hide taskbar.
     */
    private static RECT awayFromAutoHideTaskbar(Monitor target, RECT monitor) {
        ShellAPI.APPBARDATA data = new ShellAPI.APPBARDATA();
        data.cbSize.setValue(data.size());
        if ((Shell32.INSTANCE.SHAppBarMessage(new DWORD(ABM_GETSTATE), data).intValue() & ABS_AUTOHIDE) == 0) return null;
        for (String cls : new String[] {"Shell_TrayWnd", "Shell_SecondaryTrayWnd"}) {
            HWND bar = null;
            while ((bar = User32.INSTANCE.FindWindowEx(null, bar, cls, null)) != null) {
                if (!target.handle.equals(User32.INSTANCE.MonitorFromWindow(bar, Win32.MONITOR_DEFAULTTONEAREST))) continue;
                RECT b = new RECT();
                User32.INSTANCE.GetWindowRect(bar, b);
                RECT r = new RECT();
                r.left = monitor.left;
                r.top = monitor.top;
                r.right = monitor.right;
                r.bottom = monitor.bottom;
                // Hidden or not, it spans the monitor along the edge it sits on.
                if (b.right - b.left >= target.width) {
                    if (b.top + b.bottom > monitor.top + monitor.bottom) r.bottom -= TASKBAR_GAP;
                    else r.top += TASKBAR_GAP;
                } else if (b.left + b.right > monitor.left + monitor.right) {
                    r.right -= TASKBAR_GAP;
                } else {
                    r.left += TASKBAR_GAP;
                }
                return r;
            }
        }
        return null;
    }

    /**
     * A window got the focus. If you switched to it (Alt+Tab, the taskbar...), it comes to you. If Windows
     * picked it by itself, because the window you were using was closed or minimized, it stays where it is,
     * and the keyboard goes to the desktop instead of to a window you may not be able to see.
     */
    private void focusChanged(HWND w) {
        if (!active) return;
        String cls = className(w);
        if (SWITCHER_CLASSES.contains(cls)) { // you're picking something; what counts is the window from before
            log("focus: " + describe(w) + ", a switcher");
            // Only the taskbar: Alt+Tab's window also takes the focus again right after the switch, and that
            // would make the next window Windows picks look like yours.
            if (TASKBAR_CLASSES.contains(cls)) {
                lastSwitcherAt = System.nanoTime();
                switcherPending = true;
                scheduleNudge(1, NUDGE_MILLIS);
            }
            return;
        }
        boolean viaSwitcher = switcherPending && System.nanoTime() - lastSwitcherAt < SWITCHED_NANOS;
        switcherPending = false;
        HWND before = lastFocused;
        lastFocused = w;
        if (w.equals(before) || DESKTOP_CLASSES.contains(cls)) return;
        // Right after something was minimized, Windows picks the next window (even when you minimized it with
        // its taskbar button). Otherwise, right after a taskbar click, you picked it. Otherwise Windows
        // picked it if the window you were using went away. Some apps focus a helper window of their own (a
        // console's, say), which is minimized a moment after the main one, so look at the main one it belongs to.
        boolean pickedByWindows = System.nanoTime() - lastMinimizedAt < JUST_MINIMIZED_NANOS
                || !viaSwitcher && before != null && (wentAway(before) || wentAway(U32.I.GetAncestor(before, GA_ROOTOWNER)));
        boolean elsewhere = isOwnWindow(w) || !inView(w);
        log("focus: " + describe(w) + (elsewhere ? " on another monitor" : " on this monitor") + ", after "
                + describe(before) + (pickedByWindows ? ", picked by Windows" : ", you switched to it"));
        if (!pickedByWindows && w.equals(game)) {
            // The way back that works on every keyboard: Right Ctrl is missing on many new laptops (a Copilot key
            // sits there) and on Macs running Windows, and switching to the game is what anyone tries (2026-09-29).
            // Our own changes to the game window never activate it (and the focus goes back to it only once this
            // session is closed), so this was you.
            listener.onGameChosen();
            return;
        }
        if (!pickedByWindows) {
            bringOver(w, "you switched to it");
        } else if (elsewhere) {
            log("it stays where it is; the keyboard goes to the desktop");
            final HWND picked = w;
            onMover(new Runnable() {
                @Override
                public void run() {
                    // Only if it still has the focus: never take it away from something you picked in the meantime.
                    if (active && picked.equals(U32.I.GetForegroundWindow())) Win32.forceForeground(U32.I.GetShellWindow());
                }
            }, SETTLE_MILLIS); // let its app finish taking the focus first (see SETTLE_MILLIS)
        }
    }

    private void scheduleNudge(final int attempt, int delayMillis) {
        onMover(new Runnable() {
            @Override
            public void run() {
                nudgeAfterTaskbarClick(attempt);
            }
        }, delayMillis);
    }

    /**
     * After a taskbar click, the window you picked sometimes didn't come forward until a key was pressed, when it
     * was on the other monitor, behind the fullscreen game. The log showed why: meanwhile nothing was in front at
     * all. Windows had handed the focus to the app, but the app hadn't taken it. Normally the window you pick
     * comes to the front and has to repaint, which wakes its app; behind the game it stays covered, so an idle
     * app (a chat app, say) sleeps through it until a key press wakes it. So wake the apps over there ourselves, with
     * an empty message rather than a key they would also receive. If the taskbar itself still has the focus,
     * tap Alt instead, which lifts Windows' block on focus changes.
     *
     * <p>Waking alone wasn't enough: a later log (2026-09-23) had four taskbar clicks for a chat app in a row with
     * nothing in front, apps woken each time, and it only came with an Alt press. With nothing in front, no
     * app can receive the Alt either, so tap it then too.
     */
    private void nudgeAfterTaskbarClick(int attempt) {
        if (!active || !switcherPending) return; // something came forward
        HWND fg = U32.I.GetForegroundWindow();
        String cls = fg == null ? "" : className(fg);
        String when = "nothing came forward after the taskbar click (check " + attempt + ")";
        if (fg == null || TASKBAR_CLASSES.contains(cls)) {
            String state = fg == null ? "nothing in front, woke " + wakeAppsElsewhere() + " app windows on other monitors"
                    : "the taskbar still has the focus";
            if ((U32.I.GetAsyncKeyState(Win32.VK_MENU) & 0x8000) == 0) {
                log(when + "; " + state + ", tapping Alt");
                U32.I.keybd_event((byte) Win32.VK_MENU, (byte) 0, 0, null);
                U32.I.keybd_event((byte) Win32.VK_MENU, (byte) 0, Win32.KEYEVENTF_KEYUP, null);
            } else {
                log(when + "; " + state + " (Alt is held)");
            }
        } else if (!SWITCHER_CLASSES.contains(cls) && !DESKTOP_CLASSES.contains(cls) && !fg.equals(lastFocused)) {
            log(when + "; in front: " + describe(fg) + ", woke " + wakeAppsElsewhere() + " app windows on other monitors");
        } else {
            log(when + "; in front: " + describe(fg));
            return;
        }
        if (attempt < NUDGES) scheduleNudge(attempt + 1, RENUDGE_MILLIS);
    }

    /** Posts an empty message to every app window on other monitors, which wakes an idle app. Returns how many. */
    private int wakeAppsElsewhere() {
        final List<HWND> windows = new ArrayList<HWND>();
        User32.INSTANCE.EnumWindows(new WinUser.WNDENUMPROC() {
            @Override
            public boolean callback(HWND w, Pointer data) {
                if (isAppWindow(w) && !onTarget(w)) windows.add(w);
                return true;
            }
        }, null);
        for (HWND w : windows) U32.I.PostMessage(w, WM_NULL, new WPARAM(0), new LPARAM(0));
        return windows.size();
    }

    /** Closed, hidden or minimized: then Windows hands the focus to a window of its choice. */
    private static boolean wentAway(HWND w) {
        return w == null || !User32.INSTANCE.IsWindow(w) || !User32.INSTANCE.IsWindowVisible(w) || U32.I.IsIconic(w);
    }

    private boolean onTarget(HWND w) {
        return target.handle.equals(User32.INSTANCE.MonitorFromWindow(w, Win32.MONITOR_DEFAULTTONEAREST));
    }

    /** On the monitor being used, and while one window is shown, with its middle over that window. */
    private boolean inView(HWND w) {
        if (!onTarget(w)) return false;
        RECT a = area(), r = new RECT();
        if (a == null || !User32.INSTANCE.GetWindowRect(w, r)) return true;
        int cx = (r.left + r.right) / 2, cy = (r.top + r.bottom) / 2;
        return cx >= a.left && cx < a.right && cy >= a.top && cy < a.bottom;
    }

    /**
     * Windows opens new windows where it likes, usually on the main monitor, which is where the game
     * is. Move real app windows onto the monitor being used instead (over the window shown, if it's just one).
     */
    private void bringOver(final HWND w, final String why) {
        if (!active || !isAppWindow(w) || U32.I.IsIconic(w) || inView(w)) return;
        onMover(new Runnable() {
            @Override
            public void run() {
                if (!active) return;
                if (U32.I.IsHungAppWindow(w)) {
                    log("not moving " + describe(w) + " (" + why + "): it's not responding");
                    return;
                }
                long start = System.nanoTime();
                String result = moveTo(w, target.work, area());
                if (result != null) moved++;
                else moveFailed++; // e.g. a window running as admin, which a normal program isn't allowed to move
                log("moving " + describe(w) + " (" + why + "): " + (result != null ? result : "Windows refused")
                        + String.format(" in %.0f ms", (System.nanoTime() - start) / 1e6));
            }
        });
    }

    private void log(String message) {
        listener.log(message);
    }

    /** "class (program.exe)", for the log. */
    private static String describe(HWND w) {
        if (w == null) return "nothing";
        if (!User32.INSTANCE.IsWindow(w)) return "a closed window";
        return className(w) + " (" + AppWindow.exeName(w) + ")";
    }

    private void onMover(Runnable task) {
        onMover(task, 0);
    }

    private void onMover(Runnable task, int delayMillis) {
        try {
            mover.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException closing) {
            // the session closed in between; nothing left to do
        }
    }

    private static boolean isOwnWindow(HWND w) {
        return AppWindow.isOwnWindow(w);
    }

    private static String className(HWND w) {
        return AppWindow.className(w);
    }

    private static boolean isAppWindow(HWND w) {
        return AppWindow.isAppWindow(w);
    }

    /**
     * Centers the window in the work area (over {@code around}, the one window being shown, if there is one),
     * shrinking it if it's too big. A maximized window slides over still maximized, in one step: un-maximizing,
     * moving and maximizing again would flash through all three. Returns what it did, for the log, or null if
     * Windows refused. Mover thread only.
     */
    private static String moveTo(HWND w, RECT work, RECT around) {
        WinUser.WINDOWPLACEMENT placement = new WinUser.WINDOWPLACEMENT();
        if (!User32.INSTANCE.GetWindowPlacement(w, placement).booleanValue()) return null;
        boolean maximized = U32.I.IsZoomed(w);
        RECT now = new RECT();
        User32.INSTANCE.GetWindowRect(w, now);
        RECT normal = maximized ? placement.rcNormalPosition : now; // the size it has when not maximized
        int areaW = work.right - work.left, areaH = work.bottom - work.top;
        // Too big for the work area: shrink it to 90%, not to exactly the work area. With an auto-hide taskbar
        // that's the whole monitor, and Windows takes a window covering all of it for a fullscreen app, which
        // keeps the taskbar from coming up.
        int winW = fitted(normal.right - normal.left, areaW), winH = fitted(normal.bottom - normal.top, areaH);
        int x = work.left + (areaW - winW) / 2, y = work.top + (areaH - winH) / 2;
        if (around != null) { // over the window being shown, where you can see it, but still inside the work area
            x = Math.max(work.left, Math.min((around.left + around.right - winW) / 2, work.right - winW));
            y = Math.max(work.top, Math.min((around.top + around.bottom - winH) / 2, work.bottom - winH));
        }
        int flags = SWP_NOZORDER | SWP_NOACTIVATE | SWP_ASYNCWINDOWPOS; // async, so a frozen app can't freeze us
        String was = (maximized ? "maximized, " : "") + (now.right - now.left) + "x" + (now.bottom - now.top)
                + (maximized ? " (un-maximized " + (normal.right - normal.left) + "x" + (normal.bottom - normal.top) + ")" : "");
        if (!maximized) {
            if (!User32.INSTANCE.SetWindowPos(w, null, x, y, winW, winH, flags)) return null;
            return "was " + was + ", now " + winW + "x" + winH;
        }

        // First the size it un-maximizes to, over there, so un-maximizing later doesn't send it back.
        int[] offset = workspaceOffset();
        placement.rcNormalPosition.left = x - offset[0];
        placement.rcNormalPosition.top = y - offset[1];
        placement.rcNormalPosition.right = x + winW - offset[0];
        placement.rcNormalPosition.bottom = y + winH - offset[1];
        placement.showCmd = SW_SHOWMAXIMIZED; // already maximized, so this doesn't move it yet
        User32.INSTANCE.SetWindowPlacement(w, placement);
        // Then slide it over, keeping how far it reaches past the work area (its invisible frame).
        WinUser.MONITORINFO from = new WinUser.MONITORINFO();
        User32.INSTANCE.GetMonitorInfo(User32.INSTANCE.MonitorFromWindow(w, Win32.MONITOR_DEFAULTTONEAREST), from);
        int left = work.left + now.left - from.rcWork.left, top = work.top + now.top - from.rcWork.top;
        int right = work.right + now.right - from.rcWork.right, bottom = work.bottom + now.bottom - from.rcWork.bottom;
        if (!User32.INSTANCE.SetWindowPos(w, null, left, top, right - left, bottom - top, flags)) return null;
        return "was " + was + ", now maximized over here (un-maximizes to " + winW + "x" + winH + ")";
    }

    private static int fitted(int size, int area) {
        return size < area ? size : area * 9 / 10;
    }

    /**
     * Window placements are in workspace coordinates, which start at the main monitor's work area rather
     * than at its corner. They only differ when the taskbar is at the top or left.
     */
    private static int[] workspaceOffset() {
        WinUser.MONITORINFO main = new WinUser.MONITORINFO();
        User32.INSTANCE.GetMonitorInfo(User32.INSTANCE.MonitorFromPoint(new POINT.ByValue(0, 0), MONITOR_DEFAULTTOPRIMARY), main);
        return new int[] {main.rcWork.left - main.rcMonitor.left, main.rcWork.top - main.rcMonitor.top};
    }
}
