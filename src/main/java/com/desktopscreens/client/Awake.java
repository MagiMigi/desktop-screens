package com.desktopscreens.client;

//? if >=26.3 {
/*import com.desktopscreens.core.Monitor;
import com.desktopscreens.core.Monitors;
import com.desktopscreens.core.Win32;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
*///?}
import net.minecraft.Util;
import net.minecraft.client.Minecraft;

/**
 * From 1.21.2 on, Minecraft slows itself down when it gets no input: 30 fps after a minute, 10 after ten (the
 * "Inactivity FPS Limit" option, on by default). Someone using the desktop gives their input to Windows, and someone
 * watching a screen gives none at all, so both looked away: a 50-minute desktop session ran at 15.7 fps on average
 * (2026-10-01), and a film on a screen would have dropped to 10 pictures a second. Using the desktop or watching a
 * live picture counts as being there. A minimized game still slows down.
 */
public final class Awake {
    /** How long after the last {@link #now} the game still counts as being used. */
    private static final long RECENT_MILLIS = 1000;
    /**
     * How long a window must stay in front of the fullscreen game on its monitor before the game counts as covered.
     * Minimizing a window on the other monitor hands the focus around for a moment (the taskbar, a window behind the
     * game) before it settles: each such moment capped a frame at 10 fps, a visible flicker (Test 78, 2026-11-05).
     */
    private static final long COVERED_MILLIS = 500;
    private static long last = Long.MIN_VALUE / 2;
    /** Since when the game has looked covered, or 0 while it doesn't. */
    private static long coveredSince;

    private Awake() {}

    /** The player is using the desktop, or a live picture is in sight. Every frame or tick while that lasts. */
    static void now(Minecraft mc) {
        last = Util.getMillis();
        //? if >=1.21.2 {
        /*mc.getFramerateLimitTracker().onInputReceived();
        *///?}
    }

    //? if >=26.3 {
    /*/^*
     * The frame rate limit Minecraft draws with ({@code limit}, from its FramerateLimitTracker), for a fullscreen game
     * that's in sight. In its "Exclusive Fullscreen" mode (a video setting, off by default), 26.3 slows a fullscreen
     * game that doesn't have the focus to 10 fps, as if minimized
     * ("window.isFocused() || !window.isExclusiveFullscreen()"). Using a screen that shows another monitor gives the
     * focus to the apps there while the game stays in sight: it ran at 10-19 fps (Test 77, 2026-11-05). So while the
     * desktop is used or a live picture is in sight, a fullscreen game that isn't minimized and isn't covered by the
     * window in front keeps its normal limit, as a windowed game would. Otherwise Minecraft's own. Covered: the window
     * in front is on the game's monitor and the game isn't topmost (in the see-through view it is, over that window),
     * for at least {@link #COVERED_MILLIS}.
     ^/
    public static int framerateLimit(Minecraft mc, FramerateLimitTracker tracker, int limit) {
        if (tracker.getThrottleReason() != FramerateLimitTracker.FramerateThrottleReason.WINDOW_ICONIFIED) return limit;
        long now = Util.getMillis();
        if (now - last > RECENT_MILLIS || mc.getWindow().isIconified() || !Win32.SUPPORTED) return limit;
        long hwnd = GameWindow.hwnd(mc);
        Monitor monitor = Monitors.of(hwnd);
        if (monitor == null) return limit;
        if (Win32.front(hwnd, monitor) == Win32.Front.SAME_MONITOR && !Win32.topmost(hwnd)) {
            if (coveredSince == 0) coveredSince = now;
            if (now - coveredSince >= COVERED_MILLIS) return limit;
        } else {
            coveredSince = 0;
        }
        return mc.options.framerateLimit().get();
    }
    *///?}
}
