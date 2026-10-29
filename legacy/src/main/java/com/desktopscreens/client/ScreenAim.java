package com.desktopscreens.client;

import com.desktopscreens.ScreenBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

/**
 * The screen the player is looking at, for G and for the hint under the crosshair: a screen that's on for you can be
 * used from across the room (up to {@link #RANGE} blocks); an off one within reach says how to turn it on.
 * Client thread only.
 */
final class ScreenAim {
    private static final double RANGE = 32;

    private static ScreenGroups.Group usable, sharedWithMe;
    private static ITextComponent hint, keys;
    /** What the hint says about which screen, so it fades per screen and shows again for another one. */
    private static String hintKey;
    private static final HintFade fade = new HintFade();

    private ScreenAim() {}

    /** Every client tick. */
    static void tick(Minecraft mc) {
        usable = sharedWithMe = null;
        hint = keys = null;
        if (mc.player == null || mc.world == null || mc.currentScreen != null) return;
        ScreenGroups.Group group = screenAt(mc, mc.player.rayTrace(RANGE, 1f));
        if (group == null) {
            fade.reset(); // a glance away and straight back shows the hint again (2026-10-15)
            return;
        }
        UUID owner = group.owner(mc.world);
        String kind;
        if (mc.player.getUniqueID().equals(owner)) {
            usable = group;
            kind = "use";
            hint = new TextComponentTranslation("desktopscreens.hint.use_screen", DesktopClient.key(DesktopClient.OPEN_KEY));
        } else if ((owner == null || mc.getConnection() == null || mc.getConnection().getPlayerInfo(owner) == null) // off, or its owner left
                && screenAt(mc, mc.objectMouseOver) == group) { // within reach, so right-clicking works
            kind = "turn on";
            hint = new TextComponentTranslation("desktopscreens.hint.turn_on_screen", DesktopClient.key(mc.gameSettings.keyBindUseItem));
        } else if (owner != null) { // someone else's: whose, and whether they share it with you
            String name = group.ownerName(mc.world);
            if (group.sharedWith(mc.world, mc.player.getUniqueID())) {
                sharedWithMe = group;
                sharedHint(mc, name, group);
                kind = WatchChoices.hidden(mc.world, group) ? "hidden" : WatchChoices.muted(mc.world, group) ? "muted" : "shared";
            } else {
                kind = "theirs";
                hint = new TextComponentTranslation("desktopscreens.hint.someones_screen", name);
            }
        } else {
            return;
        }
        hintKey = kind + " " + group.anchor();
    }

    /** Whose it is and what we chose for it, and with an empty hand, the keys to change that ({@link WatchChoices}). */
    private static void sharedHint(Minecraft mc, String name, ScreenGroups.Group group) {
        boolean hidden = WatchChoices.hidden(mc.world, group), muted = WatchChoices.muted(mc.world, group);
        hint = new TextComponentTranslation(hidden ? "desktopscreens.hint.shared_hidden" : muted ? "desktopscreens.hint.shared_muted"
                : "desktopscreens.hint.shared_with_you", name);
        if (!mc.player.getHeldItemMainhand().isEmpty()) return;
        ITextComponent use = DesktopClient.key(mc.gameSettings.keyBindUseItem);
        ITextComponent shiftUse = DesktopClient.key(mc.gameSettings.keyBindSneak, mc.gameSettings.keyBindUseItem);
        keys = hidden ? new TextComponentTranslation("desktopscreens.hint.shared_keys_hidden", shiftUse)
                : new TextComponentTranslation(muted ? "desktopscreens.hint.shared_keys_muted" : "desktopscreens.hint.shared_keys", use, shiftUse);
    }

    private static ScreenGroups.Group screenAt(Minecraft mc, RayTraceResult hit) {
        if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) return null;
        BlockPos pos = hit.getBlockPos();
        if (!(mc.world.getBlockState(pos).getBlock() instanceof ScreenBlock)) return null;
        return ScreenGroups.of(mc.world, pos);
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
    static boolean renderHud(Minecraft mc, int width, int height) {
        if (hint == null || mc.gameSettings.hideGUI || mc.currentScreen != null) return false;
        return fade.draw(mc.fontRenderer, hintKey, keys == null ? Collections.singletonList(hint) : Arrays.asList(hint, keys),
                width / 2, height / 2 + 16, 0xE0E0E0);
    }
}
