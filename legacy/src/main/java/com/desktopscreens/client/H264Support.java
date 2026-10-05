package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.core.H264Decoder;
import com.desktopscreens.core.Win32;

/**
 * Whether this game can decode H.264 for shared screens (Windows' own decoder, through Media Foundation). Tried once
 * when the client starts, on a thread of its own; until then, and if it fails (Windows N without the Media Feature
 * Pack, or not Windows), shared screens come as JPEG. Also switched off for the session if decoding ever fails.
 */
final class H264Support {
    private static volatile boolean canDecode;

    private H264Support() {}

    static void check() {
        if (!Win32.SUPPORTED) return;
        Thread t = new Thread(() -> {
            try {
                H264Decoder.open().close();
                canDecode = true;
            } catch (RuntimeException | LinkageError e) {
                DesktopScreens.LOG.info("Sharing: no H.264 decoder ({}); shared screens will come as JPEG", e.getMessage());
            }
        }, "Desktop Screens H.264 check");
        t.setDaemon(true);
        t.start();
    }

    static boolean canDecode() {
        return canDecode;
    }

    /** Decoding failed: JPEG from now on (the server hears it with the next "watching"). */
    static void failed(Throwable why) {
        if (canDecode) DesktopScreens.LOG.error("Sharing: H.264 decoding failed; JPEG from now on", why);
        canDecode = false;
    }
}
