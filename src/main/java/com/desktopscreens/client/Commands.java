package com.desktopscreens.client;

import com.desktopscreens.core.AppWindow;
import com.desktopscreens.core.Monitor;
import com.desktopscreens.core.Monitors;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The commands that act on a window, whoever picked it: Right Ctrl + P and + W (the window under the mouse), the VR
 * command bar (the window clicked after its button). Each returns the message to show. Game thread.
 */
final class Commands {
    private Commands() {}

    /**
     * The window, or with null the whole monitor the session shows, as picture-in-picture (it shows once you're back
     * in the game); the one it shows already, off again. Pins without a Tablet too, and says it shows once you carry one.
     */
    static Component pin(Minecraft mc, DesktopSession session, AppWindow window) {
        Monitor on = window != null ? Monitors.of(window.handle()) : null;
        Monitor monitor = on != null ? on : session.target();
        if (!PictureInPicture.pinOrHide(window, monitor)) return Component.translatable("desktopscreens.pip.off");
        Component what = window != null ? Component.literal(shorten(window.label())) : monitorLabel(session, monitor);
        if (!DesktopClient.hasTablet(mc)) return Component.translatable("desktopscreens.pip.pinned_no_tablet", what);
        return Component.translatable(DesktopClient.vr("desktopscreens.pip.on"), what, DesktopClient.hostKey("P"));
    }

    /** Just this window, or with null the whole monitor again. Saved for whatever is being used (a screen keeps its own). */
    static Component oneWindow(DesktopSession session, AppWindow window) {
        session.setWindow(window);
        DesktopConfig.save();
        return window == null
                ? Component.translatable("desktopscreens.hint.whole_monitor", monitorLabel(session, session.target()))
                : Component.translatable(DesktopClient.vr("desktopscreens.hint.one_window"), shorten(window.label()), DesktopClient.hostKey("W"));
    }

    /** The monitor after the one the session shows, in Windows' order (Right Ctrl + M, Next monitor); null with just one. */
    static Monitor nextMonitor(DesktopSession session) {
        List<Monitor> monitors = Monitors.list();
        if (monitors.size() < 2) return null;
        int index = 0;
        for (int i = 0; i < monitors.size(); i++) {
            if (monitors.get(i).sameAs(session.target())) index = i;
        }
        return monitors.get((index + 1) % monitors.size());
    }

    /** The whole of this monitor from now on (leaving the one window, if one was shown); keeps control if it had it. */
    static void setMonitor(DesktopSession session, Monitor monitor) {
        if (session.window() != null) session.setWindow(null);
        session.setTarget(monitor);
        session.choice().monitor = monitor.name;
        session.choice().changed();
        DesktopConfig.save();
    }

    /** The monitor Minecraft is on is marked: there your clicks go through the game window. */
    static Component monitorLabel(DesktopSession session, Monitor monitor) {
        return session.isGameMonitor(monitor)
                ? Component.translatable("desktopscreens.settings.monitor_game", monitor.label())
                : Component.literal(monitor.label());
    }

    /** Window titles can be long (a video's, say). */
    static String shorten(String text) {
        return text.length() <= 48 ? text : text.substring(0, 45) + "...";
    }
}
