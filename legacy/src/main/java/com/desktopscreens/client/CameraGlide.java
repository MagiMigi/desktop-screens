package com.desktopscreens.client;

import com.desktopscreens.core.PictureFit;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
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
    /** Where a glide ends: straight in front of the middle of a picture this big (in blocks), looking at it. */
    static final class Aim {
        final Vec3d middle;
        final float width, height;
        final EnumFacing facing;

        Aim(Vec3d middle, float width, float height, EnumFacing facing) {
            this.middle = middle;
            this.width = width;
            this.height = height;
            this.facing = facing;
        }
    }

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
    private static double drawnFov(Minecraft mc, EntityViewRenderEvent.CameraSetup event) {
        double fov = mc.gameSettings.fovSetting;
        // Limited as the game limits it when it draws.
        if (event.getEntity() instanceof AbstractClientPlayer) fov *= MathHelper.clamp(((AbstractClientPlayer) event.getEntity()).getFovModifier(), 0.1F, 1.5F);
        return event.getState().getMaterial() == Material.WATER ? fov * 60 / 70 : fov;
    }

    /**
     * Forge's camera event, every frame before Minecraft turns the view: where the camera should look from instead of
     * the player's eyes. The event's angles are the view's (yaw with 180 added, as Minecraft turns it); Minecraft then
     * turns by them and moves to the eyes, so the rest of the way (from the eyes to the glide's point) goes in first,
     * turned into the view's axes.
     */
    static void apply(EntityViewRenderEvent.CameraSetup event) {
        if (aim == null) return;
        if (out && progress() >= 1) {
            aim = null;
            return;
        }
        Entity entity = event.getEntity();
        float partial = (float) event.getRenderPartialTicks();
        Vec3d eyes = new Vec3d(entity.prevPosX + (entity.posX - entity.prevPosX) * partial,
                entity.prevPosY + (entity.posY - entity.prevPosY) * partial + entity.getEyeHeight(),
                entity.prevPosZ + (entity.posZ - entity.prevPosZ) * partial);
        // Worked out each frame from the field of view being drawn, which can settle during the glide.
        Minecraft mc = Minecraft.getMinecraft();
        double aspect = mc.displayWidth / (double) Math.max(1, mc.displayHeight);
        double distance = PictureFit.glideDistance(aim.width, aim.height, drawnFov(mc, event), aspect);
        Vec3d to = aim.middle.add(aim.facing.getXOffset() * distance, 0, aim.facing.getZOffset() * distance);
        float yaw = event.getYaw() - 180, pitch = event.getPitch();
        double t = eased();
        float turn = MathHelper.wrapDegrees(aim.facing.getOpposite().getHorizontalAngle() - yaw); // the short way round
        Vec3d position = eyes.add(to.subtract(eyes).scale(t));
        float newYaw = yaw + (float) (turn * t), newPitch = (float) (pitch - pitch * t);
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
}
