package com.desktopscreens.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.EntityViewRenderEvent;

/**
 * Glides the camera into a screen in the world and back out. Using a screen that shows Minecraft's own monitor
 * works like this: the camera glides in until the picture fills the view, then the game switches to the in-place
 * view, where the real screen shows through the game window exactly where the picture was. Leaving glides back to
 * the player's eyes. Client thread only.
 *
 * <p>On 1.12.2 through Forge's camera event ({@link #apply}), which can turn the camera; moving it is a translation
 * added there, in the view's own axes.
 */
final class CameraGlide {
    /** Where the camera looks from: eye position and angles, in Minecraft's degrees. */
    static final class Pose {
        final Vec3d position;
        final float yaw, pitch;

        Pose(Vec3d position, float yaw, float pitch) {
            this.position = position;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

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
        Vec3d middle = group.frontPoint(rect[0] + rect[2] / 2, rect[1] + rect[3] / 2);
        double tan = Math.tan(Math.toRadians(mc.gameSettings.fovSetting) / 2); // the vertical field of view
        double aspect = mc.displayWidth / (double) Math.max(1, mc.displayHeight);
        double distance = Math.max(rect[3] / 2 / tan, rect[2] / 2 / (tan * aspect));
        Vec3d position = middle.add(group.facing.getXOffset() * distance, 0, group.facing.getZOffset() * distance);
        return new Pose(position, group.facing.getOpposite().getHorizontalAngle(), 0);
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
     * Forge's camera event, every frame before Minecraft turns the view: where the camera should look from instead of
     * the player's eyes. The event's angles are the view's (yaw with 180 added, as Minecraft turns it); Minecraft then
     * turns by them and moves to the eyes, so the rest of the way (from the eyes to the glide's point) goes in first,
     * turned into the view's axes.
     */
    static void apply(EntityViewRenderEvent.CameraSetup event) {
        if (target == null) return;
        double p = progress();
        if (out && p >= 1) {
            target = null;
            return;
        }
        Entity entity = event.getEntity();
        float partial = (float) event.getRenderPartialTicks();
        Vec3d eyes = new Vec3d(entity.prevPosX + (entity.posX - entity.prevPosX) * partial,
                entity.prevPosY + (entity.posY - entity.prevPosY) * partial + entity.getEyeHeight(),
                entity.prevPosZ + (entity.posZ - entity.prevPosZ) * partial);
        float yaw = event.getYaw() - 180, pitch = event.getPitch();
        double t = out ? 1 - p : p;
        t = t * t * (3 - 2 * t); // eases in and out
        float turn = MathHelper.wrapDegrees(target.yaw - yaw); // the short way round
        Vec3d position = eyes.add(target.position.subtract(eyes).scale(t));
        float newYaw = yaw + (float) (turn * t), newPitch = (float) (pitch + (target.pitch - pitch) * t);
        event.setYaw(newYaw + 180);
        event.setPitch(newPitch);
        event.setRoll(0);
        // From the camera to the eyes, in the view's axes: turned about y by the view's yaw, then about x by its pitch,
        // as Minecraft turns the world after this.
        Vec3d d = eyes.subtract(position);
        double yawRad = Math.toRadians(newYaw + 180), pitchRad = Math.toRadians(newPitch);
        double cy = Math.cos(yawRad), sy = Math.sin(yawRad), cp = Math.cos(pitchRad), sp = Math.sin(pitchRad);
        double x1 = d.x * cy + d.z * sy, z1 = -d.x * sy + d.z * cy, y1 = d.y;
        double y2 = y1 * cp - z1 * sp, z2 = y1 * sp + z1 * cp;
        GlStateManager.translate(x1, y2, z2);
    }

    /** Whether the camera is away from the player's eyes (gliding, or at a screen). */
    static boolean active() {
        return target != null;
    }
}
