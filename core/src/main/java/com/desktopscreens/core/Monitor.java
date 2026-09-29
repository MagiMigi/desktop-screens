package com.desktopscreens.core;

import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinUser;

/** A monitor, in physical pixels on the Windows desktop. */
public final class Monitor {
    /** Windows' device name, like {@code \\.\DISPLAY2}. */
    public final String name;
    public final int x, y, width, height;
    public final boolean primary;
    final WinUser.HMONITOR handle;
    /** The monitor minus the taskbar, where windows get moved to. */
    final RECT work;

    Monitor(String name, WinUser.HMONITOR handle, RECT bounds, RECT work, boolean primary) {
        this.name = name;
        this.handle = handle;
        this.x = bounds.left;
        this.y = bounds.top;
        this.width = bounds.right - bounds.left;
        this.height = bounds.bottom - bounds.top;
        this.work = work;
        this.primary = primary;
    }

    public boolean sameAs(Monitor other) {
        return other != null && handle.equals(other.handle);
    }

    public boolean contains(int px, int py) {
        return px >= x && py >= y && px < x + width && py < y + height;
    }

    RECT rect() {
        RECT r = new RECT();
        r.left = x;
        r.top = y;
        r.right = x + width;
        r.bottom = y + height;
        return r;
    }

    /** Short name for the UI, like "DISPLAY2 1024x768". */
    public String label() {
        return shortLabel() + " " + width + "x" + height;
    }

    /** Just the name, like "DISPLAY2", for where the size doesn't fit. */
    public String shortLabel() {
        return name.replace("\\\\.\\", "");
    }

    @Override
    public String toString() {
        return label() + " at " + x + "," + y + (primary ? " (main)" : "");
    }
}
