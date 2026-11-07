package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.core.SoftFullscreen;
import com.desktopscreens.core.Win32;
import net.minecraft.client.Minecraft;

/**
 * F11 without the flash when the desktop is used ({@link SoftFullscreen}), for players who use it. Being composed may
 * cost a little delay, so a player who never uses the desktop keeps F11 exactly as it was: no cost while unused. The
 * first use shows the flash once, and it stays remembered in the config. Off with {@link DesktopConfig#fullscreenComposed}.
 */
final class ComposedFullscreen {
    private ComposedFullscreen() {}

    /** The desktop is about to be used: remembered from now on, and in F11 the window changes right away. */
    static void desktopUsed(Minecraft mc) {
        DesktopConfig.load();
        if (DesktopConfig.desktopUsed) return;
        DesktopConfig.desktopUsed = true;
        DesktopConfig.save();
        tick(mc);
    }

    /** Every client tick, as F11 comes and goes, and Minecraft makes a new window with it. */
    static void tick(Minecraft mc) {
        if (!Win32.SUPPORTED) return;
        DesktopConfig.load();
        if (!DesktopConfig.desktopUsed || !DesktopConfig.fullscreenComposed || !mc.isFullScreen()) return;
        String done = SoftFullscreen.keep(GameWindow.hwnd(), true); // 1.12.2 goes on drawing its old size: see SoftFullscreen.grown
        if (done != null) DesktopScreens.LOG.info("Fullscreen: {}, so Windows keeps composing the game and using the desktop doesn't flash", done);
    }
}
