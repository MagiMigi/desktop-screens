package com.desktopscreens.core;

/**
 * A key's place on the keyboard, from the number USB gives it (its HID usage, which SDL uses as its scan codes, and
 * Minecraft from 26.3 on) to the scan code Windows reports for it, as {@link DesktopInput#setHostKey} takes it:
 * 0x100 added for extended keys, so Right Ctrl is 0x11D. The same table as SDL's for Windows, the other way round.
 */
public final class UsbScancodes {
    private static final int[] WINDOWS = new int[0xE8];

    static {
        // A to Z (USB 0x04-0x1D).
        int[] letters = {0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24, 0x25, 0x26, 0x32,
                0x31, 0x18, 0x19, 0x10, 0x13, 0x1F, 0x14, 0x16, 0x2F, 0x11, 0x2D, 0x15, 0x2C};
        for (int i = 0; i < letters.length; i++) WINDOWS[0x04 + i] = letters[i];
        for (int i = 0; i < 10; i++) WINDOWS[0x1E + i] = 0x02 + i; // 1 to 9, then 0
        int[] row = {0x1C, 0x01, 0x0E, 0x0F, 0x39, 0x0C, 0x0D, 0x1A, 0x1B, 0x2B, 0x2B, 0x27, 0x28, 0x29, 0x33, 0x34, 0x35, 0x3A};
        for (int i = 0; i < row.length; i++) WINDOWS[0x28 + i] = row[i]; // Enter ... Caps Lock (USB 0x28-0x39)
        for (int i = 0; i < 10; i++) WINDOWS[0x3A + i] = 0x3B + i; // F1 to F10
        WINDOWS[0x44] = 0x57; // F11
        WINDOWS[0x45] = 0x58; // F12
        int[] nav = {0x137, 0x46, 0x45, 0x152, 0x147, 0x149, 0x153, 0x14F, 0x151, 0x14D, 0x14B, 0x150, 0x148};
        for (int i = 0; i < nav.length; i++) WINDOWS[0x46 + i] = nav[i]; // Print Screen ... Up (USB 0x46-0x52)
        int[] keypad = {0x145, 0x135, 0x37, 0x4A, 0x4E, 0x11C, 0x4F, 0x50, 0x51, 0x4B, 0x4C, 0x4D, 0x47, 0x48, 0x49, 0x52, 0x53};
        for (int i = 0; i < keypad.length; i++) WINDOWS[0x53 + i] = keypad[i]; // Num Lock ... keypad . (USB 0x53-0x63)
        WINDOWS[0x64] = 0x56; // the extra key left of Z on European keyboards
        WINDOWS[0x65] = 0x15D; // Menu
        WINDOWS[0x67] = 0x59; // keypad =
        int[] high = {0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x6B, 0x6C, 0x6D, 0x6E, 0x76};
        for (int i = 0; i < high.length; i++) WINDOWS[0x68 + i] = high[i]; // F13 to F24
        WINDOWS[0x87] = 0x73; // Japanese and Brazilian keys
        WINDOWS[0x88] = 0x70;
        WINDOWS[0x89] = 0x7D;
        WINDOWS[0x8A] = 0x79;
        WINDOWS[0x8B] = 0x7B;
        int[] mods = {0x1D, 0x2A, 0x38, 0x15B, 0x11D, 0x36, 0x138, 0x15C};
        for (int i = 0; i < mods.length; i++) WINDOWS[0xE0 + i] = mods[i]; // left Ctrl, Shift, Alt, Windows; then the right ones
    }

    private UsbScancodes() {}

    /** Windows' scan code for the key USB numbers {@code usage}, or 0 if it isn't on a usual keyboard. */
    public static int toWindows(int usage) {
        return usage > 0 && usage < WINDOWS.length ? WINDOWS[usage] : 0;
    }
}
