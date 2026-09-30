package com.desktopscreens.mixin;

import com.desktopscreens.client.ScreenBuilderClient;
import com.desktopscreens.client.WatchChoices;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands right- and left-clicks to the Screen Builder before Minecraft uses them, so a click that builds or cancels
 * can't also open a chest or break a block; and right-clicks on screens others share with us, which mute or hide them
 * ({@link WatchChoices}). The loaders' own click events differ too much to share.
 */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void desktopscreens$use(CallbackInfo ci) {
        if (ScreenBuilderClient.onUse() || WatchChoices.onUse()) ci.cancel();
    }

    /** True tells Minecraft not to go on mining this tick. */
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void desktopscreens$attack(CallbackInfoReturnable<Boolean> cir) {
        if (ScreenBuilderClient.onAttack()) cir.setReturnValue(true);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void desktopscreens$keepAttacking(boolean leftClick, CallbackInfo ci) {
        if (leftClick && ScreenBuilderClient.holdAttack()) ci.cancel();
    }
}
