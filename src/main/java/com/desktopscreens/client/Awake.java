package com.desktopscreens.client;

import net.minecraft.client.Minecraft;

/**
 * From 1.21.2 on, Minecraft slows itself down when it gets no input: 30 fps after a minute, 10 after ten (the
 * "Inactivity FPS Limit" option, on by default). Someone using the desktop gives their input to Windows, and someone
 * watching a screen gives none at all, so both looked away: a 50-minute desktop session ran at 15.7 fps on average
 * (2026-10-01), and a film on a screen would have dropped to 10 pictures a second. Using the desktop or watching a
 * live picture counts as being there. A minimized game still slows down.
 */
final class Awake {
    private Awake() {}

    /** The player is using the desktop, or a live picture is in sight. Every frame or tick while that lasts. */
    static void now(Minecraft mc) {
        //? if >=1.21.2 {
        /*mc.getFramerateLimitTracker().onInputReceived();
        *///?}
    }
}
