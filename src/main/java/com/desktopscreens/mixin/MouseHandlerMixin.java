package com.desktopscreens.mixin;

import com.desktopscreens.client.WorldPointing;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=1.21.9 {
/*import net.minecraft.client.input.MouseButtonInfo;
*///?}

/**
 * While pointing at a screen in the world ({@link WorldPointing}): Vivecraft presses Minecraft's attack and use as real
 * mouse buttons, which then click the PC instead, and the game hears no button at all; the wheel scrolls the PC. With
 * the test switch the mouse stays free over the game window meanwhile, as it's the laser.
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
    //? if >=1.21.9 {
    /*@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void desktopscreens$button(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (WorldPointing.onButton(info.button(), action)) ci.cancel();
    }
    *///?} else {
    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void desktopscreens$button(long window, int button, int action, int modifiers, CallbackInfo ci) {
        if (WorldPointing.onButton(button, action)) ci.cancel();
    }
    //?}

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void desktopscreens$scroll(long window, double x, double y, CallbackInfo ci) {
        if (WorldPointing.onScroll(y)) ci.cancel();
    }

    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void desktopscreens$grab(CallbackInfo ci) {
        if (WorldPointing.keepsMouseFree()) ci.cancel();
    }
}
