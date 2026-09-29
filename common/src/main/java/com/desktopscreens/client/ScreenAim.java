package com.desktopscreens.client;

import com.desktopscreens.ScreenBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;
import java.util.UUID;

/**
 * The screen the player is looking at, for G and for the hint under the crosshair: a screen that's on for you can be
 * used from across the room (up to {@link #RANGE} blocks); an off one within reach says how to turn it on.
 * Client thread only.
 */
final class ScreenAim {
    private static final double RANGE = 32;

    private static ScreenGroups.Group usable, sharedWithMe;
    private static Component hint, keys;
    /** What the hint says about which screen, so it fades per screen and shows again for another one. */
    private static String hintKey;
    private static final HintFade fade = new HintFade();

    private ScreenAim() {}

    /** Every client tick. */
    static void tick(Minecraft mc) {
        usable = sharedWithMe = null;
        hint = keys = null;
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        ScreenGroups.Group group = screenAt(mc, mc.player.pick(RANGE, 1f, false));
        if (group == null) return;
        UUID owner = group.owner(mc.level);
        String kind;
        if (mc.player.getUUID().equals(owner)) {
            usable = group;
            kind = "use";
            hint = Component.translatable("desktopscreens.hint.use_screen", DesktopClient.key(DesktopClient.OPEN_KEY));
        } else if ((owner == null || mc.player.connection.getPlayerInfo(owner) == null) // off, or its owner left
                && screenAt(mc, mc.hitResult) == group) { // within reach, so right-clicking works
            kind = "turn on";
            hint = Component.translatable("desktopscreens.hint.turn_on_screen", DesktopClient.key(mc.options.keyUse));
        } else if (owner != null) { // someone else's: whose, and whether they share it with you
            String name = group.ownerName(mc.level);
            if (group.sharedWith(mc.level, mc.player.getUUID())) {
                sharedWithMe = group;
                sharedHint(mc, name, group);
                kind = WatchChoices.hidden(mc.level, group) ? "hidden" : WatchChoices.muted(mc.level, group) ? "muted" : "shared";
            } else {
                kind = "theirs";
                hint = Component.translatable("desktopscreens.hint.someones_screen", name);
            }
        } else {
            return;
        }
        hintKey = kind + " " + group.anchor();
    }

    /** Whose it is and what we chose for it, and with an empty hand, the keys to change that ({@link WatchChoices}). */
    private static void sharedHint(Minecraft mc, String name, ScreenGroups.Group group) {
        boolean hidden = WatchChoices.hidden(mc.level, group), muted = WatchChoices.muted(mc.level, group);
        hint = Component.translatable(hidden ? "desktopscreens.hint.shared_hidden" : muted ? "desktopscreens.hint.shared_muted"
                : "desktopscreens.hint.shared_with_you", name);
        if (!mc.player.getMainHandItem().isEmpty()) return;
        Component use = DesktopClient.key(mc.options.keyUse), shiftUse = DesktopClient.key(mc.options.keyShift, mc.options.keyUse);
        keys = hidden ? Component.translatable("desktopscreens.hint.shared_keys_hidden", shiftUse)
                : Component.translatable(muted ? "desktopscreens.hint.shared_keys_muted" : "desktopscreens.hint.shared_keys", use, shiftUse);
    }

    private static ScreenGroups.Group screenAt(Minecraft mc, HitResult hit) {
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos pos = blockHit.getBlockPos();
        if (!(mc.level.getBlockState(pos).getBlock() instanceof ScreenBlock)) return null;
        return ScreenGroups.of(mc.level, pos);
    }

    /** The screen G would use now: one that's on for this player, looked at. Null otherwise. */
    static ScreenGroups.Group usable() {
        return usable;
    }

    /** Someone else's screen, looked at, that they share with this player. Null otherwise. */
    static ScreenGroups.Group sharedWithMe() {
        return sharedWithMe;
    }

    /** Under the crosshair, while playing, for a few seconds ({@link HintFade}). True if it's showing. */
    static boolean renderHud(Minecraft mc, GuiGraphics g) {
        if (hint == null || mc.options.hideGui || mc.screen != null) return false;
        return fade.draw(g, mc.font, hintKey, keys == null ? List.of(hint) : List.of(hint, keys), g.guiWidth() / 2, g.guiHeight() / 2 + 16, 0xE0E0E0);
    }
}
