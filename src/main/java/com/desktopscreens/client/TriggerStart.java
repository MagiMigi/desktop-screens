package com.desktopscreens.client;

import com.desktopscreens.ScreenBlock;
import com.desktopscreens.ScreenBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashSet;
import java.util.Set;

/**
 * In VR with controllers, and with the test switch: pulling the trigger (Minecraft's attack) with the laser on your own
 * screen while it's on starts using it, as G does (2026-11-01): G isn't on any controller button unless the player puts
 * it there, so a VR player who never reads notes had no way in. "In the world", that same pull already clicks the PC
 * where the laser is; "On a panel", the panel opens once the pull is let go ({@link #tick}), and the pull goes nowhere,
 * its release neither. Whatever is in the hand, except the Screen Builder, which keeps its own trigger actions on screens; and the trigger no longer breaks your
 * own screen while it's on ({@link #keepsFromBreaking}). The mixins on the mouse and keyboard call this before
 * {@link WorldPointing}. Game thread.
 */
public final class TriggerStart {
    /** Buttons and keys whose press asked for the panel (or did nothing): their repeats and release are kept from the game too. */
    private static final Set<Integer> buttonsKept = new HashSet<>(), keysKept = new HashSet<>();
    /** "On a panel": the screen whose panel opens once the pull that asked for it is let go ({@link #tick}); else null. */
    private static ScreenGroups.Group pending;

    private TriggerStart() {}

    /** A mouse button; true if it's kept from the game. */
    public static boolean onButton(int button, int action) {
        if (action != InputConstants.PRESS) return action == InputConstants.RELEASE ? buttonsKept.remove(button) : buttonsKept.contains(button);
        buttonsKept.remove(button); // pressed again: its last release never came
        Minecraft mc = Minecraft.getInstance();
        if (!WorldPointing.bound(mc.options.keyAttack, InputConstants.Type.MOUSE, button)) return false;
        ScreenGroups.Group screen = starting(mc);
        if (screen == null) return false;
        if (start(mc, screen)) WorldPointing.pressAtStart(mc, button, false);
        else buttonsKept.add(button);
        return true;
    }

    /** A key (the trigger can be bound to one); true if it's kept from the game. */
    public static boolean onKey(int key, int action) {
        if (action != InputConstants.PRESS) return action == InputConstants.RELEASE ? keysKept.remove(key) : keysKept.contains(key);
        keysKept.remove(key);
        Minecraft mc = Minecraft.getInstance();
        if (!WorldPointing.bound(mc.options.keyAttack, InputConstants.Type.KEYSYM, key)) return false;
        ScreenGroups.Group screen = starting(mc);
        if (screen == null) return false;
        if (start(mc, screen)) WorldPointing.pressAtStart(mc, key, true);
        else keysKept.add(key);
        return true;
    }

    /**
     * Starts using {@code screen} for a pull: pointing at it ("In the world") at once, which this pull then clicks with
     * (true); a panel only once the pull is let go (false; see {@link #tick}).
     */
    private static boolean start(Minecraft mc, ScreenGroups.Group screen) {
        if (!VrMode.pointing()) {
            pending = screen;
            return false;
        }
        DesktopClient.useScreen(mc, screen);
        return WorldPointing.active();
    }

    /**
     * Every client tick: the panel a pull asked for opens once it's let go, as the Tablet's does. Opened during the pull,
     * in VR it opened while Vivecraft was still handling the trigger, and Vivecraft's guard against a trigger held into a
     * new menu ends with that, so the same pull could have clicked the PC (the final review, 2026-11-01). The screen is
     * checked again, in case it was turned off meanwhile.
     */
    static void tick(Minecraft mc) {
        if (pending == null || !buttonsKept.isEmpty() || !keysKept.isEmpty()) return;
        ScreenGroups.Group asked = pending;
        pending = null;
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        ScreenGroups.Group screen = ownScreenOn(mc, asked.anchor());
        if (screen != null) DesktopClient.useScreen(mc, screen);
    }

    /** The screen a pull of the trigger starts using now, or null: it acts in the game as usual then. */
    private static ScreenGroups.Group starting(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.screen != null || WorldPointing.active()) return null;
        if (!VrMode.testing() && !VrMode.vrActive()) return null; // PC and seated VR: G, as before
        if (VrMode.radialShowing() || VrMode.keyboardShowing()) return null; // the trigger picks there
        if (ScreenBuilder.builderHand(mc.player) != null) return null;
        return aimed(mc);
    }

    /**
     * Your own screen that's on, with the laser on any of its blocks (the frame too) up to as far as "Use the screen"
     * reaches ({@link ScreenAim#RANGE}); null otherwise, also when another block is in the way.
     */
    static ScreenGroups.Group aimed(Minecraft mc) {
        BlockHitResult hit = ScreenAim.laserHit(mc);
        return hit != null ? ownScreenOn(mc, hit.getBlockPos()) : null;
    }

    /**
     * The trigger doesn't break your own screen while it's on, in VR with controllers and with the test switch: it starts
     * using it instead. To break it, turn it off first (A), or use the Screen Builder. Also keeps a block broken next to
     * it from carrying on into it.
     */
    public static boolean keepsFromBreaking(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !VrMode.testing() && !VrMode.vrActive()) return false;
        return ScreenBuilder.builderHand(mc.player) == null && ownScreenOn(mc, pos) != null;
    }

    /** The screen at {@code pos} if it's on and this player's, as {@link ScreenAim} calls usable; else null. */
    private static ScreenGroups.Group ownScreenOn(Minecraft mc, BlockPos pos) {
        if (!(mc.level.getBlockState(pos).getBlock() instanceof ScreenBlock)) return null;
        ScreenGroups.Group group = ScreenGroups.of(mc.level, pos);
        return group != null && mc.player.getUUID().equals(group.owner(mc.level)) ? group : null;
    }
}
