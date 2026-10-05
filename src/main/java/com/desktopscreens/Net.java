package com.desktopscreens;

//? if >=1.20.5 {
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?} else {
/*import com.desktopscreens.net.CustomPacketPayload;
import com.desktopscreens.net.Payloads;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
*///?}
import net.minecraft.server.level.ServerPlayer;

/** Sends our messages from the server to players (the client's side is {@code client.ClientNet}). */
public final class Net {
    private Net() {}

    public static void toPlayer(ServerPlayer player, CustomPacketPayload payload) {
        //? if >=1.20.5 {
        player.connection.send(new ClientboundCustomPayloadPacket(payload));
        //?} else {
        /*player.connection.send(new ClientboundCustomPayloadPacket(payload.type().id(), Payloads.encode(payload)));
        *///?}
    }
}
