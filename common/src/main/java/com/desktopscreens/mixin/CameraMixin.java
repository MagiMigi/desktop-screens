package com.desktopscreens.mixin;

import com.desktopscreens.client.CameraGlide;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets {@link CameraGlide} move the camera into a screen in the world and back. Neither loader has a hook for that. */
@Mixin(Camera.class)
abstract class CameraMixin {
    @Shadow
    protected abstract void setPosition(Vec3 pos);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Shadow
    public abstract Vec3 getPosition();

    @Shadow
    public abstract float getYRot();

    @Shadow
    public abstract float getXRot();

    /** After Minecraft placed the camera at the player's eyes for this frame. */
    @Inject(method = "setup", at = @At("TAIL"))
    private void desktopscreens$glide(BlockGetter level, Entity entity, boolean detached, boolean thirdPersonReverse,
                                      float partialTick, CallbackInfo ci) {
        CameraGlide.Pose pose = CameraGlide.apply(getPosition(), getYRot(), getXRot());
        if (pose == null) return;
        setRotation(pose.yaw(), pose.pitch());
        setPosition(pose.position());
    }
}
