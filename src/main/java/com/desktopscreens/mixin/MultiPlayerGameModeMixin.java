package com.desktopscreens.mixin;

import com.desktopscreens.client.WorldPointing;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While pointing at a screen in the world ({@link WorldPointing}), that screen can't be broken or switched off by hand:
 * Vivecraft breaks blocks a controller swings into, and uses ones it touches with the trigger, through these, not
 * through the attack and use buttons the pointing takes.
 */
@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeMixin {
    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void desktopscreens$startBreaking(BlockPos pos, Direction side, CallbackInfoReturnable<Boolean> cir) {
        if (WorldPointing.protects(pos)) cir.setReturnValue(false);
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void desktopscreens$keepBreaking(BlockPos pos, Direction side, CallbackInfoReturnable<Boolean> cir) {
        if (WorldPointing.protects(pos)) cir.setReturnValue(false);
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void desktopscreens$use(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        if (WorldPointing.protects(hit.getBlockPos())) cir.setReturnValue(InteractionResult.FAIL);
    }
}
