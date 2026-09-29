package com.desktopscreens.mixin;

import com.desktopscreens.ShareStream;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands shared sound to its own thread straight from the network thread. Both loaders pass our packets to the game's
 * thread, which takes them once a frame, and a game in the background can draw only about one frame a second (on
 * Linux with Hyprland, on another workspace: the sound came in bursts, 7 s of every 10 cut; 2026-09-25). This runs
 * before either loader sees the packet; their handlers stay as they were, in case this ever doesn't.
 */
@Mixin(ClientboundCustomPayloadPacket.class)
abstract class ClientboundCustomPayloadPacketMixin {
    @Inject(method = "handle(Lnet/minecraft/network/protocol/common/ClientCommonPacketListener;)V", at = @At("HEAD"), cancellable = true)
    private void desktopscreens$sound(ClientCommonPacketListener listener, CallbackInfo ci) {
        ShareStream.Client client = ShareStream.client;
        if (((ClientboundCustomPayloadPacket) (Object) this).payload() instanceof ShareStream.Sound sound && client != null) {
            client.sound(sound);
            ci.cancel();
        }
    }
}
