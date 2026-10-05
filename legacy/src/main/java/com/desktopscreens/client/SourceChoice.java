package com.desktopscreens.client;

import net.minecraft.world.World;

import java.util.UUID;

/**
 * What a desktop view or a screen in the world shows: a monitor (its Windows device name, like {@code \\.\DISPLAY2};
 * empty picks one) and maybe just one window on it ("program|class", never the title; empty for the whole monitor).
 * The tablet has one ({@link DesktopConfig#tablet}: what G and the tablet open), and so does each screen that was given
 * its own while it was used, kept in this player's config under the screen's id. A screen without its own shows what
 * the tablet does. Client thread only.
 */
final class SourceChoice {
    /** The screen it's for, or null for the tablet's. */
    final UUID screen;
    String monitor, window;
    /**
     * Which window exactly (its handle), 0 if unknown: two windows of one program share a {@link #window} key, and
     * with the key alone two screens showed the same Chrome window. See {@code AppWindow.find}.
     */
    long handle;

    SourceChoice(UUID screen, String monitor, String window, long handle) {
        this.screen = screen;
        this.monitor = monitor;
        this.window = window;
        this.handle = handle;
    }

    /** What it shows, as one string: screens showing the same share a capture. */
    String key() {
        return monitor + "\n" + window + "\n" + handle;
    }

    /** Called after changing it: a screen's choice is its own from then on (saved with the config). */
    void changed() {
        if (screen != null) DesktopConfig.screens.put(screen, this);
    }

    /** What {@code group} shows: its own choice, or the tablet's. */
    static SourceChoice of(World world, ScreenGroups.Group group) {
        return of(group.screenId(world));
    }

    /** What the screen with this id shows: its own choice, or the tablet's (also for null). */
    static SourceChoice of(UUID screen) {
        DesktopConfig.load();
        SourceChoice own = screen == null ? null : DesktopConfig.screens.get(screen);
        return own != null ? own : DesktopConfig.tablet;
    }

    /**
     * For using {@code group}: its own choice, or a copy of the tablet's that becomes its own once changed. A screen
     * without an id changes the tablet's; turning it off and on gives it one.
     */
    static SourceChoice forUsing(World world, ScreenGroups.Group group) {
        DesktopConfig.load();
        UUID id = group.screenId(world);
        if (id == null) return DesktopConfig.tablet;
        SourceChoice own = DesktopConfig.screens.get(id);
        return own != null ? own : new SourceChoice(id, DesktopConfig.tablet.monitor, DesktopConfig.tablet.window, DesktopConfig.tablet.handle);
    }
}
