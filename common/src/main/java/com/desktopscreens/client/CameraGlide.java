package com.desktopscreens.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Glides the camera into a screen in the world and back out. Using a screen that shows Minecraft's own monitor
 * works like this: the camera glides in until the picture fills the view, then the game switches to the in-place
 * view, where the real screen shows through the game window exactly where the picture was. Leaving glides back to
 * the player's eyes. The camera mixin asks {@link #apply} every frame. Client thread only.
 */
public final class CameraGlide {
    /** Where the camera looks from: eye position and angles, in Minecraft's degrees. */
    public record Pose(Vec3 position, float yaw, float pitch) {}

    private static final long DURATION_NANOS = 450_000_000L;

    private static Pose target;
    private static long startNanos;
    /** On the way back to the player's eyes. */
    private static boolean out;

    private CameraGlide() {}

    /**
     * Straight in front of the picture's middle, looking at it, just far enough away that all of it is in view.
     * {@code rect} is the picture on the screen, from {@link ScreenGroups.Group#pictureRect}.
     */
    static Pose facing(ScreenGroups.Group group, float[] rect, Minecraft mc) {
        Vec3 middle = group.frontPoint(rect[0] + rect[2] / 2, rect[1] + rect[3] / 2);
        double tan = Math.tan(Math.toRadians(mc.options.fov().get()) / 2); // the vertical field of view
        double aspect = mc.getWindow().getWidth() / (double) Math.max(1, mc.getWindow().getHeight());
        double distance = Math.max(rect[3] / 2 / tan, rect[2] / 2 / (tan * aspect));
        Vec3 position = middle.add(group.facing.getStepX() * distance, 0, group.facing.getStepZ() * distance);
        return new Pose(position, group.facing.getOpposite().toYRot(), 0);
    }

    /** Starts gliding from wherever the camera is now to {@code to}, and stays there until {@link #out}. */
    static void in(Pose to) {
        target = to;
        out = false;
        startNanos = System.nanoTime();
    }

    /** Back to the player's eyes, from wherever the glide is now (also halfway in). */
    static void out() {
        if (target == null) return;
        double reached = out ? 1 - progress() : progress();
        out = true;
        startNanos = System.nanoTime() - (long) ((1 - reached) * DURATION_NANOS);
    }

    /** True once the glide in, or out, has arrived. */
    static boolean done() {
        return target == null || progress() >= 1;
    }

    /** Puts the camera back at the player's eyes right away. */
    static void stop() {
        target = null;
    }

    private static double progress() {
        return Math.min(1, (System.nanoTime() - startNanos) / (double) DURATION_NANOS);
    }

    /**
     * Called by the camera mixin with where Minecraft put the camera this frame (the player's eyes). Returns where
     * it should be instead, or null to leave it.
     */
    public static Pose apply(Vec3 position, float yaw, float pitch) {
        if (target == null) return null;
        double p = progress();
        if (out && p >= 1) {
            target = null;
            return null;
        }
        double t = out ? 1 - p : p;
        t = t * t * (3 - 2 * t); // eases in and out
        float turn = Mth.wrapDegrees(target.yaw() - yaw); // the short way round
        return new Pose(position.lerp(target.position(), t), yaw + (float) (turn * t), (float) Mth.lerp(t, pitch, target.pitch()));
    }
}
