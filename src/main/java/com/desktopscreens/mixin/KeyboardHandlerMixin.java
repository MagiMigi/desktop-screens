package com.desktopscreens.mixin;

import com.desktopscreens.client.WorldPointing;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=1.21.9 {
/*import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
*///?}

/**
 * While pointing at a screen in the world ({@link WorldPointing}): Vivecraft's keyboard types on the PC, and its menu
 * button (Escape) goes back to the game instead of opening the pause menu. Other keys stay the game's.
 */
@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
    //? if >=1.21.9 {
    /*@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void desktopscreens$key(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (WorldPointing.onKey(event.key(), action)) ci.cancel();
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void desktopscreens$character(long window, CharacterEvent event, CallbackInfo ci) {
        if (WorldPointing.onChar(event.codepoint())) ci.cancel();
    }
    *///?} else {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void desktopscreens$key(long window, int key, int scanCode, int action, int modifiers, CallbackInfo ci) {
        if (WorldPointing.onKey(key, action)) ci.cancel();
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void desktopscreens$character(long window, int codePoint, int modifiers, CallbackInfo ci) {
        if (WorldPointing.onChar(codePoint)) ci.cancel();
    }
    //?}
}
