package com.desktopscreens.core;

import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.LONG;
import com.sun.jna.platform.win32.WinDef.WORD;
import com.sun.jna.platform.win32.WinUser;

import java.util.HashSet;
import java.util.Set;

/**
 * Clicks, the mouse wheel and keys sent to the PC as if from the real mouse and keyboard (SendInput). For the VR
 * panel: Vivecraft's laser, menu buttons and keyboard reach the game, and from there the real desktop. Keys go to
 * the window in front; our own keyboard hook ({@link InputSession}) lets them pass, as it only acts while the host
 * key is held. Remembers what it pressed, so {@link #releaseAll} can let go of anything still held. Any thread.
 */
public final class InjectedInput {
    private static final int MOUSEEVENTF_MOVE = 0x1, MOUSEEVENTF_LEFTDOWN = 0x2, MOUSEEVENTF_LEFTUP = 0x4, MOUSEEVENTF_RIGHTDOWN = 0x8,
            MOUSEEVENTF_RIGHTUP = 0x10, MOUSEEVENTF_MIDDLEDOWN = 0x20, MOUSEEVENTF_MIDDLEUP = 0x40, MOUSEEVENTF_WHEEL = 0x800,
            MOUSEEVENTF_VIRTUALDESK = 0x4000, MOUSEEVENTF_ABSOLUTE = 0x8000;
    private static final int KEYEVENTF_EXTENDEDKEY = 0x1, KEYEVENTF_KEYUP = 0x2, KEYEVENTF_UNICODE = 0x4;
    private static final int WHEEL_DELTA = 120, MAPVK_VK_TO_VSC = 0, VK_LBUTTON = 0x01, VK_RBUTTON = 0x02, VK_MBUTTON = 0x04;
    private static final int SM_XVIRTUALSCREEN = 76, SM_YVIRTUALSCREEN = 77, SM_CXVIRTUALSCREEN = 78, SM_CYVIRTUALSCREEN = 79;
    /** Keys that sit apart from the number pad's: Windows needs the "extended" flag to tell them from it. */
    private static final Set<Integer> EXTENDED = new HashSet<Integer>(java.util.Arrays.asList(
            0x21, 0x22, 0x23, 0x24, // Page Up, Page Down, End, Home
            0x25, 0x26, 0x27, 0x28, // the arrows
            0x2D, 0x2E, // Insert, Delete
            0xA3, 0xA5)); // Right Ctrl, Right Alt

    /** In every input sent here (its "extra info"), so our own keyboard hook knows it and lets it pass ({@link #isOurs}). */
    static final long MARK = 0x4453_4B54L; // "DSKT"

    /** Mouse buttons (Minecraft's numbering: 0 left, 1 right, 2 middle) and keys pressed here and not let go yet. */
    private static final boolean[] buttonsDown = new boolean[3];
    private static final Set<Integer> keysDown = new HashSet<Integer>();

    private InjectedInput() {}

    /**
     * Puts the real cursor on a desktop point. Like the clicks, it goes in line with the other input, so a click sent
     * before it still lands where it was meant to (SetCursorPos would jump the queue).
     */
    public static boolean moveTo(int x, int y) {
        WinUser.INPUT[] in = inputs(1);
        mouseAt(in[0], 0, 0, x, y);
        return send(in);
    }

    /**
     * Presses or lets go of a mouse button on a desktop point: 0 left, 1 right, 2 middle; others do nothing. The
     * point travels with the click, so it lands there whenever Windows gets to it, wherever the cursor is by then.
     */
    public static synchronized boolean buttonAt(int button, boolean down, int x, int y) {
        int flags = buttonFlags(button, down);
        if (flags == 0) return false;
        buttonsDown[button] = down;
        WinUser.INPUT[] in = inputs(1);
        mouseAt(in[0], flags, 0, x, y);
        return send(in);
    }

    /** Presses or lets go of a mouse button wherever the cursor is (a release after the pointer left the picture). */
    public static synchronized boolean button(int button, boolean down) {
        int flags = buttonFlags(button, down);
        if (flags == 0) return false;
        buttonsDown[button] = down;
        WinUser.INPUT[] in = inputs(1);
        mouse(in[0], flags, 0);
        return send(in);
    }

    /** Turns the wheel over a desktop point: {@code amount} notches, away from you when positive. */
    public static boolean wheelAt(double amount, int x, int y) {
        int delta = (int) Math.round(amount * WHEEL_DELTA);
        if (delta == 0) return false;
        WinUser.INPUT[] in = inputs(1);
        mouseAt(in[0], MOUSEEVENTF_WHEEL, delta, x, y);
        return send(in);
    }

    /**
     * A desktop coordinate as SendInput's absolute 0-65535 across the virtual desktop ({@code origin} and {@code size}
     * along this axis). Windows turns it back into floor(value * size / 65536), so this is rounded up to land on the
     * very pixel.
     */
    static int normalized(int coordinate, int origin, int size) {
        return (int) (((long) (coordinate - origin) * 65536 + size - 1) / size);
    }

    private static int buttonFlags(int button, boolean down) {
        switch (button) {
            case 0: return down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP;
            case 1: return down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP;
            case 2: return down ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_MIDDLEUP;
            default: return 0;
        }
    }

    /** Presses or lets go of a key, by its Windows virtual key. */
    public static synchronized boolean key(int vk, boolean down) {
        if (down) keysDown.add(vk);
        else keysDown.remove(vk);
        int scan = User32.INSTANCE.MapVirtualKeyEx(vk, MAPVK_VK_TO_VSC, User32.INSTANCE.GetKeyboardLayout(0));
        WinUser.INPUT[] in = inputs(1);
        key(in[0], vk, scan, (down ? 0 : KEYEVENTF_KEYUP) | (EXTENDED.contains(vk) ? KEYEVENTF_EXTENDEDKEY : 0));
        return send(in);
    }

    /** Types one character, whatever the keyboard layout. */
    public static boolean typeChar(char c) {
        WinUser.INPUT[] in = inputs(2);
        key(in[0], 0, c, KEYEVENTF_UNICODE);
        key(in[1], 0, c, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP);
        return send(in);
    }

    /** Lets go of every mouse button and key pressed here, so nothing stays held when the view closes. */
    public static synchronized void releaseAll() {
        for (int b = 0; b < buttonsDown.length; b++) {
            if (buttonsDown[b]) button(b, false);
        }
        for (Integer vk : keysDown.toArray(new Integer[0])) key(vk, false);
    }

    /** Whether a real mouse button (left, right or middle) is held down right now. */
    public static boolean anyButtonDown() {
        return (Win32.U32.I.GetAsyncKeyState(VK_LBUTTON) & 0x8000) != 0 || (Win32.U32.I.GetAsyncKeyState(VK_RBUTTON) & 0x8000) != 0
                || (Win32.U32.I.GetAsyncKeyState(VK_MBUTTON) & 0x8000) != 0;
    }

    /**
     * Waits until Windows has handled what was sent and the cursor is on a desktop point, at most {@code maxMillis}.
     * SendInput only queues: without this, the next read of the cursor could still see an earlier move. True if it got there.
     */
    public static boolean waitForCursor(int x, int y, long maxMillis) {
        long until = System.nanoTime() + maxMillis * 1_000_000L;
        while (true) {
            int[] p = Win32.cursorPos();
            if (p != null && p[0] == x && p[1] == y) return true;
            if (System.nanoTime() >= until) return false;
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Whether input carrying this "extra info" was sent here. */
    static boolean isOurs(long extraInfo) {
        return extraInfo == MARK;
    }

    /** Brings a window to the front, so keys go to it. True if it's in front now. */
    public static boolean bringForward(long window) {
        Win32.forceForeground(Win32.hwnd(window));
        return Win32.foregroundWindow() == window;
    }

    /** {@code count} inputs side by side in one block of memory, as SendInput reads them. */
    private static WinUser.INPUT[] inputs(int count) {
        return (WinUser.INPUT[]) new WinUser.INPUT().toArray(count);
    }

    private static void mouse(WinUser.INPUT input, int flags, int data) {
        input.type = new DWORD(WinUser.INPUT.INPUT_MOUSE);
        input.input.setType("mi");
        input.input.mi.dx = new LONG(0);
        input.input.mi.dy = new LONG(0);
        input.input.mi.mouseData = new DWORD(data);
        input.input.mi.dwFlags = new DWORD(flags);
        input.input.mi.time = new DWORD(0);
        input.input.mi.dwExtraInfo = new ULONG_PTR(MARK);
    }

    /** A mouse input that first moves to a desktop point (across all monitors), then does {@code flags}. */
    private static void mouseAt(WinUser.INPUT input, int flags, int data, int x, int y) {
        User32 u = User32.INSTANCE;
        mouse(input, flags | MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK, data);
        input.input.mi.dx = new LONG(normalized(x, u.GetSystemMetrics(SM_XVIRTUALSCREEN), u.GetSystemMetrics(SM_CXVIRTUALSCREEN)));
        input.input.mi.dy = new LONG(normalized(y, u.GetSystemMetrics(SM_YVIRTUALSCREEN), u.GetSystemMetrics(SM_CYVIRTUALSCREEN)));
    }

    private static void key(WinUser.INPUT input, int vk, int scan, int flags) {
        input.type = new DWORD(WinUser.INPUT.INPUT_KEYBOARD);
        input.input.setType("ki");
        input.input.ki.wVk = new WORD(vk);
        input.input.ki.wScan = new WORD(scan);
        input.input.ki.dwFlags = new DWORD(flags);
        input.input.ki.time = new DWORD(0);
        input.input.ki.dwExtraInfo = new ULONG_PTR(MARK);
    }

    /** Hands the inputs to Windows in one go. True if it took all of them. */
    private static boolean send(WinUser.INPUT[] inputs) {
        return User32.INSTANCE.SendInput(new DWORD(inputs.length), inputs, inputs[0].size()).intValue() == inputs.length;
    }
}
