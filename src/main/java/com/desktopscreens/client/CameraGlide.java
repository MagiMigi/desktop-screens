package com.desktopscreens.client;

import com.desktopscreens.core.PictureFit;
import com.desktopscreens.mixin.GameRendererAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

    /** Where a glide ends: straight in front of the middle of a picture this big (in blocks), looking at it. */
    record Aim(Vec3 middle, float width, float height, Direction facing) {}

    private static final long DURATION_NANOS = 450_000_000L;

    private static Aim aim;
    /** The screen being glided to: its {@link ScreenGroups.Group#anchor}, which outlives the group object. */
    private static BlockPos screen;
    private static long startNanos;
    /** On the way back to the player's eyes. */
    private static boolean out;

    private CameraGlide() {}

    /** Where to glide for the picture at {@code rect} on the screen, from {@link ScreenGroups.Group#pictureRect}. */
    static Aim facing(ScreenGroups.Group group, float[] rect) {
        return new Aim(group.frontPoint(rect[0] + rect[2] / 2, rect[1] + rect[3] / 2), rect[2], rect[3], group.facing);
    }

    /** Starts gliding from wherever the camera is now to {@code to}, in front of {@code screen}, and stays there until {@link #out}. */
    static void in(Aim to, BlockPos screen) {
        aim = to;
        CameraGlide.screen = screen;
        out = false;
        startNanos = System.nanoTime();
    }

    /** The picture it glided to changed size (Frames, switched in front of it): the camera goes by the new one. */
    static void aimAt(Aim to) {
        if (aim != null) aim = to;
    }

    /** Back to the player's eyes, from wherever the glide is now (also halfway in). */
    static void out() {
        if (aim == null) return;
        double reached = out ? 1 - progress() : progress();
        out = true;
        startNanos = System.nanoTime() - (long) ((1 - reached) * DURATION_NANOS);
    }

    /** True once the glide in, or out, has arrived. */
    static boolean done() {
        return aim == null || progress() >= 1;
    }

    /** Puts the camera back at the player's eyes right away. */
    static void stop() {
        aim = null;
    }

    private static double progress() {
        return Math.min(1, (System.nanoTime() - startNanos) / (double) DURATION_NANOS);
    }

    /** How far the camera has come from the player's eyes, 0 to 1, easing in and out. */
    private static double eased() {
        double p = progress();
        double t = out ? 1 - p : p;
        return t * t * (3 - 2 * t);
    }

    /**
     * How far the picture of the screen at {@code anchor} has eased from stretched (0) to its true shape (1): in step
     * with the camera, so that on arrival it fills the view exactly as the real desktop takes its place, without a jump.
     */
    static float straightened(BlockPos anchor) {
        return aim != null && anchor.equals(screen) ? (float) eased() : 0;
    }

    /** The field of view the world is drawn with, not just the setting: flying and sprinting widen it, water narrows it. */
    static double drawnFov(Minecraft mc, Camera camera) {
        //? if >=26.1 {
        /*return camera.getFov();
        *///?} else if >=1.21 {
        return ((GameRendererAccessor) mc.gameRenderer).desktopscreens$fov(camera, camera.getPartialTickTime(), true);
        //?} else {
        /*return ((GameRendererAccessor) mc.gameRenderer).desktopscreens$fov(camera, mc.getFrameTime(), true);
        *///?}
    }

    /**
     * Called by the camera mixin with where Minecraft put the camera this frame (the player's eyes). Returns where
     * it should be instead, or null to leave it.
     */
    public static Pose apply(Camera camera, Vec3 position, float yaw, float pitch) {
        if (aim == null) return null;
        if (out && progress() >= 1) {
            aim = null;
            return null;
        }
        // Worked out each frame from the field of view being drawn, which can settle during the glide: flying at a
        // field of view of 110 ended the glide with the picture filling only 81 % of the view (Test 95).
        Minecraft mc = Minecraft.getInstance();
        double aspect = mc.getWindow().getWidth() / (double) Math.max(1, mc.getWindow().getHeight());
        double distance = PictureFit.glideDistance(aim.width(), aim.height(), drawnFov(mc, camera), aspect);
        Vec3 to = aim.middle().add(aim.facing().getStepX() * distance, 0, aim.facing().getStepZ() * distance);
        double t = eased();
        float turn = Mth.wrapDegrees(aim.facing().getOpposite().toYRot() - yaw); // the short way round
        return new Pose(position.lerp(to, t), yaw + (float) (turn * t), (float) Mth.lerp(t, pitch, 0));
    }
}
