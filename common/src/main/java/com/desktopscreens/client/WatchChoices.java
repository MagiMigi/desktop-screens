package com.desktopscreens.client;

import com.desktopscreens.ShareStream;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

import java.util.Set;
import java.util.UUID;

/**
 * What this player chose for other players' shared screens, with an empty hand on one: right-click mutes it (the
 * picture stays, the sound goes; the owner's next nearest screen is heard instead) or unmutes it; Shift + right-click
 * hides it (dark and silent, and the server sends nothing for it) or shows it again. Remembered in the config until
 * changed. Since 2026-09-24.
 *
 * <p>Per screen since each screen shows and plays its own thing (hiding one of two hid both, 2026-09-26),
 * by the screen's id; screens turned on before they had ids go by their owner, all together, as before. Client
 * thread only.
 */
public final class WatchChoices {
    /** The use button is still down from a click taken here: Minecraft repeats the use every tick while it's held. */
    private static boolean useHeld;

    private WatchChoices() {}

    /** Whether this player muted {@code group}, someone else's screen. */
    static boolean muted(Level level, ScreenGroups.Group group) {
        return has(DesktopConfig.muted, level, group);
    }

    /** Whether this player hid {@code group}, someone else's screen. */
    static boolean hidden(Level level, ScreenGroups.Group group) {
        return has(DesktopConfig.hidden, level, group);
    }

    private static boolean has(Set<UUID> choices, Level level, ScreenGroups.Group group) {
        UUID key = key(level, group);
        if (key == null) return false;
        DesktopConfig.load();
        return choices.contains(key);
    }

    /** What a choice for {@code group} is remembered by: its id, or its owner's for a screen without one; null while it's off. */
    private static UUID key(Level level, ScreenGroups.Group group) {
        UUID id = group.screenId(level);
        return id != null ? id : group.owner(level);
    }

    /** Every client tick. */
    static void tick(Minecraft mc) {
        if (!mc.options.keyUse.isDown()) useHeld = false;
    }

    /**
     * Right-click, from the mixin: with an empty hand on a screen shared with us, mutes or hides it (with Shift), or
     * undoes that. A hidden one comes back with either. True if it took the click, so Minecraft does nothing with it.
     */
    public static boolean onUse() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !mc.player.getMainHandItem().isEmpty()) return false;
        ScreenGroups.Group screen = ScreenAim.sharedWithMe();
        if (screen == null) return false;
        if (useHeld) return true;
        useHeld = true;
        UUID owner = screen.owner(mc.level), key = key(mc.level, screen);
        if (owner == null || key == null) return true;
        DesktopConfig.load();
        if (DesktopConfig.hidden.contains(key)) {
            DesktopConfig.hidden.remove(key);
        } else if (mc.player.isShiftKeyDown()) {
            DesktopConfig.hidden.add(key);
            // Right away, not once the picture would have lingered: it may free a stream for another screen.
            ShareWatching.stopPictures(new ShareWatching.Screen(owner, ShareStream.idOf(screen.screenId(mc.level))));
        } else if (!DesktopConfig.muted.remove(key)) {
            DesktopConfig.muted.add(key);
        }
        DesktopConfig.save();
        return true;
    }
}
