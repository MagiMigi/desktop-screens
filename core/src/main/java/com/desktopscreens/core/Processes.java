package com.desktopscreens.core;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Tlhelp32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The programs running right now: each one's file name and the program that started it. A snapshot. */
final class Processes {
    private final Map<Integer, Integer> parents = new HashMap<Integer, Integer>();
    private final Map<Integer, String> names = new HashMap<Integer, String>();

    Processes() {
        WinNT.HANDLE snapshot = Kernel32.INSTANCE.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPPROCESS, new WinDef.DWORD(0));
        if (WinNT.INVALID_HANDLE_VALUE.equals(snapshot)) return;
        try {
            Tlhelp32.PROCESSENTRY32.ByReference entry = new Tlhelp32.PROCESSENTRY32.ByReference();
            if (!Kernel32.INSTANCE.Process32First(snapshot, entry)) return;
            do {
                parents.put(entry.th32ProcessID.intValue(), entry.th32ParentProcessID.intValue());
                names.put(entry.th32ProcessID.intValue(), Native.toString(entry.szExeFile));
            } while (Kernel32.INSTANCE.Process32Next(snapshot, entry));
        } finally {
            Kernel32.INSTANCE.CloseHandle(snapshot);
        }
    }

    Set<Integer> all() {
        return names.keySet();
    }

    /** Like {@code chrome.exe}, or "?" for one that's gone. */
    String name(int pid) {
        String name = names.get(pid);
        return name != null ? name : "?";
    }

    /**
     * The process and the ones that started it, nearest first. Stops at one that's gone: Windows reuses the numbers,
     * so a long-gone parent's number may belong to something else by now.
     */
    List<Integer> selfAndParents(int pid) {
        List<Integer> chain = new ArrayList<Integer>();
        Set<Integer> seen = new HashSet<Integer>();
        for (int p = pid; p != 0 && names.containsKey(p) && seen.add(p); ) {
            chain.add(p);
            Integer parent = parents.get(p);
            p = parent != null ? parent : 0;
        }
        return chain;
    }

    /** Whether the program's file name, without ".exe" and ignoring case, starts with one of {@code prefixes}. */
    boolean nameStartsWith(int pid, List<String> prefixes) {
        String name = name(pid).toLowerCase(Locale.ROOT);
        if (name.endsWith(".exe")) name = name.substring(0, name.length() - 4);
        for (String prefix : prefixes) {
            if (!prefix.isEmpty() && name.startsWith(prefix)) return true;
        }
        return false;
    }
}
