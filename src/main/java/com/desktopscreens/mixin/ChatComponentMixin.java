package com.desktopscreens.mixin;

import com.desktopscreens.client.WorldPointing;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While pointing at a screen in the world ({@link WorldPointing}), Vivecraft's note about typing with no menu open is
 * left out of the chat: typing there is what pointing is for.
 */
@Mixin(ChatComponent.class)
abstract class ChatComponentMixin {
    //? if >=26.1 {
    /*@Inject(method = "addClientSystemMessage", at = @At("HEAD"), cancellable = true)
    *///?} else {
    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), cancellable = true)
    //?}
    private void desktopscreens$message(Component message, CallbackInfo ci) {
        if (WorldPointing.dropsMessage(message)) ci.cancel();
    }
}
