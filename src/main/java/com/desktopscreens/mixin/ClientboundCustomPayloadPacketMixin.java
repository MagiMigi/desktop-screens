package com.desktopscreens.mixin;

//? if >=1.20.5 {
import com.desktopscreens.ShareStream;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
/*import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
*///?}
import org.spongepowered.asm.mixin.Mixin;

/**
 * Hands shared sound to its own thread straight from the network thread. Both loaders pass our packets to the game's
 * thread, which takes them once a frame, and a game in the background can draw only about one frame a second (on
 * Linux with Hyprland, on another workspace: the sound came in bursts, 7 s of every 10 cut; 2026-09-25). This runs
 * before either loader sees the packet; their handlers stay as they were, in case this ever doesn't. From 26.1 on the
 * server's receipts for our own pictures ({@code ShareStream.Taken}) go the same way.
 *
 * <p>Before 1.20.5 there's nothing to do here: both loaders give us the packet on the network thread, and
 * {@code net.Payloads} sends the sound on from there.
 */
@Mixin(ClientboundCustomPayloadPacket.class)
abstract class ClientboundCustomPayloadPacketMixin {
    //? if >=1.20.5 {
    @Inject(method = "handle(Lnet/minecraft/network/protocol/common/ClientCommonPacketListener;)V", at = @At("HEAD"), cancellable = true)
    private void desktopscreens$sound(ClientCommonPacketListener listener, CallbackInfo ci) {
        ShareStream.Client client = ShareStream.client;
        if (client == null) return;
        Object payload = ((ClientboundCustomPayloadPacket) (Object) this).payload();
        if (payload instanceof ShareStream.Sound sound) {
            client.sound(sound);
            ci.cancel();
        }
        //? if >=26.1 {
        /*// The server has a picture of ours, so the next may go: read at once, not when the game next draws a frame
        // (a minimized 26.1 game draws 10 a second, and sharing went no faster, 2026-10-02).
        if (payload instanceof ShareStream.Taken taken) {
            client.taken(taken);
            ci.cancel();
        }
        *///?}
    }
    //?}
}
