package com.desktopscreens.core;

/**
 * Who gets a press of the "Use the desktop" key (G unless changed). Minecraft tells every binding on a key about each
 * press, and big packs have many on G (13 others in All the Mods 11), so without this another mod's screen could open
 * instead of ours, and a Tablet carried in the inventory opened the desktop for a G meant for another mod (2026-11-06).
 */
public final class OpenKeyRule {
    public enum Result {
        /** Not ours to take: every binding on the key gets the press as usual. */
        PASS,
        /** Ours alone: it's for the screen looked at (or being used). */
        SCREEN,
        /** Ours alone: it's for the Tablet in the inventory. */
        TABLET
    }

    private OpenKeyRule() {}

    /**
     * What a press means. {@code debugHeld}: F3, so it's one of Minecraft's debug combos (F3 + G). {@code atScreen}: looking
     * at a screen that's on for this player, or already using one. {@code hostHeld}: the host key, Right Ctrl unless
     * changed. {@code keyFree}: no other mod reacts to this key. {@code vr}: in VR with controllers, where there's no host
     * key to hold, so a Tablet keeps working as it always did: the press goes on to every binding, this mod's included.
     */
    public static Result press(boolean debugHeld, boolean atScreen, boolean hasTablet, boolean hostHeld, boolean keyFree, boolean vr) {
        if (debugHeld) return Result.PASS;
        if (atScreen) return Result.SCREEN;
        if (!hasTablet || vr) return Result.PASS;
        return hostHeld || keyFree ? Result.TABLET : Result.PASS;
    }

    /**
     * Whether a binding on the same key counts as another mod using it. Minecraft's own don't: on 26.x its Quick Actions
     * key and the F3 + G combo's name sit on G by default, and G would never be free.
     */
    public static boolean countsAsOther(String bindingName) {
        return !"key.quickActions".equals(bindingName) && !bindingName.startsWith("key.debug.");
    }
}
