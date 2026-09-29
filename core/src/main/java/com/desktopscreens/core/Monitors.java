package com.desktopscreens.core;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinUser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class Monitors {
    private Monitors() {}

    /** All monitors, sorted by Windows' device name (DISPLAY1, DISPLAY2...). */
    public static List<Monitor> list() {
        final List<Monitor> list = new ArrayList<Monitor>();
        User32.INSTANCE.EnumDisplayMonitors(null, null, new WinUser.MONITORENUMPROC() {
            @Override
            public int apply(WinUser.HMONITOR hMonitor, HDC hdc, RECT rect, LPARAM data) {
                WinUser.MONITORINFOEX info = new WinUser.MONITORINFOEX();
                User32.INSTANCE.GetMonitorInfo(hMonitor, info);
                list.add(new Monitor(Native.toString(info.szDevice), hMonitor, info.rcMonitor, info.rcWork, (info.dwFlags & 1) != 0));
                return 1;
            }
        }, new LPARAM(0));
        Collections.sort(list, new Comparator<Monitor>() {
            @Override
            public int compare(Monitor a, Monitor b) {
                return a.name.compareTo(b.name);
            }
        });
        return list;
    }

    /** The monitor a window is mostly on. */
    public static Monitor of(long window) {
        WinUser.HMONITOR h = User32.INSTANCE.MonitorFromWindow(Win32.hwnd(window), Win32.MONITOR_DEFAULTTONEAREST);
        for (Monitor m : list()) {
            if (m.handle.equals(h)) return m;
        }
        return null;
    }

    /** The monitor with this Windows device name, or null if it's gone (unplugged, or never chosen). */
    public static Monitor byName(String name) {
        if (name == null || name.isEmpty()) return null;
        for (Monitor m : list()) {
            if (m.name.equals(name)) return m;
        }
        return null;
    }

    /**
     * The monitor to show when nothing else was chosen: the first one the game isn't on, because you
     * can only control a monitor the game isn't covering. With one monitor, that's the game's own.
     */
    public static Monitor defaultTarget(Monitor game) {
        List<Monitor> all = list();
        for (Monitor m : all) {
            if (!m.sameAs(game)) return m;
        }
        return game != null ? game : all.get(0);
    }
}
