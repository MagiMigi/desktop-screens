package com.desktopscreens.client;

import com.desktopscreens.ScreenBlock;
import com.desktopscreens.ScreenBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * The screen the player is looking at, for G and for the hint under the crosshair: a screen that's on for you can be
 * used from across the room (up to {@link #RANGE} blocks); an off one within reach says how to turn it on.
 * <p>
 * In VR with controllers, and with the test switch, the hint is about the screen the laser is on and shows at that
 * screen, where the bar goes while using it ({@link #hintFrame}): Vivecraft puts the HUD on the off hand's wrist, away
 * from the screen looked at (2026-11-01). It names the controller's buttons then. G keeps aiming with the head: a pick
 * on Vivecraft's radial menu points the laser at the radial, not at the screen. Client thread only.
 */
final class ScreenAim {
    static final double RANGE = 32;

    private static ScreenGroups.Group usable, sharedWithMe;
    private static Component hint, keys;
    /** What the hint says about which screen, so it fades per screen and shows again for another one. */
    private static String hintKey;
    private static final HintFade fade = new HintFade();
    /**
     * In VR with controllers or with the test switch, as of the last tick: the hint shows at the screen then, about the
     * one the laser is on ({@link #hintScreen}), from the block that would draw the bar ({@link #hintBlock}), over the
     * screen when the bar would go there ({@link #hintAbove}).
     */
    private static boolean atScreen, hintAbove;
    private static ScreenGroups.Group hintScreen;
    private static BlockPos hintBlock;

    private ScreenAim() {}

    /** Every client tick. */
    static void tick(Minecraft mc) {
        usable = sharedWithMe = hintScreen = null;
        hint = keys = null;
        hintBlock = null;
        atScreen = VrMode.testing() || VrMode.vrActive();
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        ScreenGroups.Group looked = screenAt(mc, mc.player.pick(RANGE, 1f, false));
        if (looked != null && mc.player.getUUID().equals(looked.owner(mc.level))) usable = looked; // G: with the head
        ScreenGroups.Group group = atScreen ? screenAt(mc, laserHit(mc)) : looked;
        if (group == null) {
            fade.reset(); // a glance away and straight back shows the hint again (2026-10-15)
            return;
        }
        UUID owner = group.owner(mc.level);
        String kind;
        if (mc.player.getUUID().equals(owner)) {
            kind = "use";
            // Not while pointing at a screen in VR: G ends that (Test 60c), and the bar is there.
            if (!WorldPointing.active()) {
                hint = Component.translatable("desktopscreens.hint.use_screen",
                        atScreen ? DesktopClient.key(Component.translatable("desktopscreens.key.vr_trigger")) : DesktopClient.key(DesktopClient.OPEN_KEY));
            }
        } else if ((owner == null || mc.player.connection.getPlayerInfo(owner) == null) // off, or its owner left
                && screenAt(mc, mc.hitResult) == group) { // within reach, so right-clicking works
            kind = "turn on";
            hint = Component.translatable("desktopscreens.hint.turn_on_screen", useKey(mc));
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
        // Not with the Screen Builder: its trigger mines the screen and its use button builds, and its own hints show
        // instead, as on PC (ScreenBuilderClient goes first there; the final review, 2026-11-01).
        if (atScreen && hint != null && ScreenBuilder.builderHand(mc.player) == null) {
            hintScreen = group;
            hintAbove = WorldPointing.barAbove(mc.level, group);
            hintBlock = WorldPointing.barBlock(group, hintAbove ? group.minRow + group.rows - 1 : group.minRow);
        }
    }

    /** Whose it is and what we chose for it, and with an empty hand, the keys to change that ({@link WatchChoices}). */
    private static void sharedHint(Minecraft mc, String name, ScreenGroups.Group group) {
        boolean hidden = WatchChoices.hidden(mc.level, group), muted = WatchChoices.muted(mc.level, group);
        hint = Component.translatable(hidden ? "desktopscreens.hint.shared_hidden" : muted ? "desktopscreens.hint.shared_muted"
                : "desktopscreens.hint.shared_with_you", name);
        if (!mc.player.getMainHandItem().isEmpty()) return;
        Component use = useKey(mc), shiftUse = DesktopClient.key(mc.options.keyShift, mc.options.keyUse);
        keys = hidden ? Component.translatable("desktopscreens.hint.shared_keys_hidden", shiftUse)
                : Component.translatable(muted ? "desktopscreens.hint.shared_keys_muted" : "desktopscreens.hint.shared_keys", use, shiftUse);
    }

    /** Minecraft's use as the hints name it: the controller's use button in VR with controllers, else its key, "[RMB]". */
    private static Component useKey(Minecraft mc) {
        return atScreen ? DesktopClient.key(Component.translatable("desktopscreens.key.vr_use")) : DesktopClient.key(mc.options.keyUse);
    }

    private static ScreenGroups.Group screenAt(Minecraft mc, HitResult hit) {
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos pos = blockHit.getBlockPos();
        if (!(mc.level.getBlockState(pos).getBlock() instanceof ScreenBlock)) return null;
        return ScreenGroups.of(mc.level, pos);
    }

    /**
     * The block the laser is on ({@link WorldPointing#laserRay}), up to {@link #RANGE} blocks away, or null: the first
     * one along it, by the blocks' outlines, through water.
     */
    static BlockHitResult laserHit(Minecraft mc) {
        double[] ray = WorldPointing.laserRay(mc);
        if (ray == null || mc.player == null || mc.level == null) return null;
        double length = Math.sqrt(ray[3] * ray[3] + ray[4] * ray[4] + ray[5] * ray[5]);
        if (length < 1e-9) return null;
        double reach = RANGE / length;
        Vec3 from = new Vec3(ray[0], ray[1], ray[2]), to = from.add(ray[3] * reach, ray[4] * reach, ray[5] * reach);
        BlockHitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    /** The screen G would use now: one that's on for this player, looked at. Null otherwise. */
    static ScreenGroups.Group usable() {
        return usable;
    }

    /** Someone else's screen, looked at (in VR, the laser on it), that they share with this player. Null otherwise. */
    static ScreenGroups.Group sharedWithMe() {
        return sharedWithMe;
    }

    /**
     * Under the crosshair, while playing, for a few seconds ({@link HintFade}). True if it's showing. In VR with
     * controllers and with the test switch it shows at the screen instead ({@link #hintFrame}), and this only says so.
     */
    static boolean renderHud(Minecraft mc, GuiGraphics g) {
        if (hint == null || mc.options.hideGui || mc.screen != null) return false;
        if (atScreen) return hintScreen != null;
        return fade.draw(g, mc.font, hintKey, hints(), g.guiWidth() / 2, g.guiHeight() / 2 + 16, 0xE0E0E0);
    }

    /**
     * The hint at the screen it's about, where the bar goes while using it, for the block at {@code pos} of {@code screen}:
     * null but for the block that draws it, in VR with controllers or with the test switch, for a few seconds as under
     * the crosshair. Not while pointing at a screen: its bar is there.
     */
    static WorldBar.Frame hintFrame(Minecraft mc, ScreenGroups.Group screen, BlockPos pos) {
        if (hintScreen == null || !pos.equals(hintBlock)) return null; // a block is one screen's
        if (mc.options.hideGui || mc.screen != null || WorldPointing.active()) return null;
        int alpha = fade.alpha(hintKey);
        return alpha < 4 ? null : WorldBar.hintFrame(mc.font, screen, hintAbove, hints(), alpha);
    }

    private static List<Component> hints() {
        return keys == null ? List.of(hint) : List.of(hint, keys);
    }
}
