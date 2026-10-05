package com.desktopscreens.client;

import net.minecraft.client.multiplayer.ClientPacketListener;
//? if >=1.20.5 {
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?} else {
/*import com.desktopscreens.net.CustomPacketPayload;
import com.desktopscreens.net.Payloads;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
*///?}

/** Sends our messages from the player's game to the server (the server's side is {@code Net}). Any thread. */
public final class ClientNet {
    private ClientNet() {}

    public static void toServer(ClientPacketListener connection, CustomPacketPayload payload) {
        //? if >=1.20.5 {
        connection.send(new ServerboundCustomPayloadPacket(payload));
        //?} else {
        /*connection.send(new ServerboundCustomPayloadPacket(payload.type().id(), Payloads.encode(payload)));
        *///?}
    }
}
