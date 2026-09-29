package com.desktopscreens.core;

import com.desktopscreens.core.Win32.U32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;

/**
 * Hands your real mouse and keyboard over to one monitor and back. While in desktop mode:
 * <ul>
 *   <li>the mouse is locked to that monitor, so apps get real input and behave normally,</li>
 *   <li>app windows that open or get focus on another monitor are moved onto it,</li>
 *   <li>the host key (Right Ctrl, or the one set with {@link #setHostKey}) belongs to us, like VirtualBox's:
 *       tapping it alone means "back to the game", and keys pressed (or the mouse wheel turned) while it's held
 *       are commands. Apps never see any of these.</li>
 * </ul>
 * Only call this from one thread (the game thread).
 */
public final class DesktopInput {
    /**
     * The host key unless another is set: Right Ctrl (its scan code 0x1D, extended). Nobody needs it for typing, and
     * Left Ctrl still works for shortcuts.
     */
    public static final int DEFAULT_HOST_KEY = 0x11D;
    /** Windows virtual-key codes for host key commands. */
    public static final int VK_F = 0x46, VK_M = 0x4D, VK_O = 0x4F, VK_P = 0x50, VK_S = 0x53, VK_W = 0x57;
    public static final int VK_0 = 0x30, VK_NUMPAD0 = 0x60, VK_ADD = 0x6B, VK_SUBTRACT = 0x6D, VK_OEM_PLUS = 0xBB, VK_OEM_MINUS = 0xBD;
    /** The arrows work on every keyboard layout (on Turkish Q, + is Shift + 4 and has no key of its own). */
    public static final int VK_UP = 0x26, VK_DOWN = 0x28;

    /** Called on the input hook thread, so implementations must return quickly. */
    public interface Listener {
        /** The host key was tapped on its own. */
        void onHostKey();

        /** {@code virtualKey} (a Windows VK_ code) was pressed while the host key was held. */
        void onHostCombo(int virtualKey);

        /** The mouse wheel turned while the host key was held: 120 per notch away from you, negative toward you. */
        void onHostWheel(int delta);

        /**
         * You switched to the game yourself (Alt+Tab, its taskbar button...): the other way back to it, which works on
         * keyboards without the host key. Not when Windows handed it the focus on its own.
         */
        void onGameChosen();

        /** What happened to which windows, for the log. Window classes and program names only, never titles. */
        void log(String message);
    }

    private final HWND game;
    private final Listener listener;
    private InputSession session;
    private int hostKey = DEFAULT_HOST_KEY;

    /** {@code gameWindow}: the game's own window (a Win32 handle), which the mouse and keyboard go back to. */
    public DesktopInput(long gameWindow, Listener listener) {
        this.game = Win32.hwnd(gameWindow);
        this.listener = listener;
    }

    public boolean isActive() {
        return session != null;
    }

    /**
     * The host key from the next {@link #enter} on, by its place on the keyboard (whatever the layout): its scan code
     * plus 0x100 for extended keys, as GLFW's {@code glfwGetKeyScancode} gives it on Windows. 0 or less: Right Ctrl.
     */
    public void setHostKey(int scancode) {
        hostKey = scancode > 0 ? scancode : DEFAULT_HOST_KEY;
    }

    /**
     * Moves the mouse to the middle of {@code target}, gives focus to the window under it, and locks the mouse
     * there. With a {@code window}, only inside that window (the whole monitor while it's minimized): the mouse
     * goes to its middle and it gets the focus.
     */
    public void enter(Monitor target, AppWindow window) {
        if (session != null) return;
        int[] area = window == null ? null : window.rectOn(target);
        int cx = target.x + target.width / 2, cy = target.y + target.height / 2;
        if (area != null) {
            cx = (area[0] + area[2]) / 2;
            cy = (area[1] + area[3]) / 2;
        }
        U32.I.SetCursorPos(cx, cy);
        if (window != null) {
            window.restore();
            Win32.forceForeground(window.hwnd);
        } else {
            // Give keyboard focus to whatever is under the mouse over there, so typing works right away.
            POINT.ByValue p = new POINT.ByValue();
            p.x = cx;
            p.y = cy;
            HWND under = U32.I.WindowFromPoint(p);
            if (under != null) Win32.forceForeground(U32.I.GetAncestor(under, Win32.GA_ROOT));
        }
        // Lock after the focus change above, because focus changes make Windows drop the lock.
        session = new InputSession(target, window, game, hostKey, listener);
    }

    /**
     * While in desktop mode: keeps the mouse inside this window, following it as it moves, resizes or is minimized,
     * or the whole monitor again with null.
     */
    public void show(AppWindow window) {
        InputSession s = session;
        if (s != null) s.show(window);
    }

    /** Gives the keyboard to this window, bringing it back first if it's minimized. */
    public void focus(AppWindow window) {
        window.restore();
        Win32.forceForeground(window.hwnd);
    }

    /** Unlocks the mouse, puts it in the middle of the game window and gives that window focus. */
    public void exit() {
        InputSession s = session;
        if (s == null) return;
        session = null;
        s.close();
        RECT r = new RECT();
        if (User32.INSTANCE.GetWindowRect(game, r)) U32.I.SetCursorPos((r.left + r.right) / 2, (r.top + r.bottom) / 2);
        Win32.forceForeground(game);
    }

    /** How many windows were moved onto the target monitor this session. */
    public int windowsMoved() {
        InputSession s = session;
        return s == null ? 0 : s.moved;
    }
}
