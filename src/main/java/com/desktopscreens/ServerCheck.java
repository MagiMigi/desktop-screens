package com.desktopscreens;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.function.BooleanSupplier;

/**
 * Lets the client tell whether the server it plays on has this mod. The server registers this payload (it's never
 * sent), and both loaders tell the client which payloads the server accepts. On a server without the mod our items
 * don't exist, and taking one from the creative menu gets the player kicked ("Failed to decode packet
 * 'serverbound/minecraft:set_creative_mode_slot'"), so the menu leaves them out there.
 */
public record ServerCheck() implements CustomPacketPayload {
    public static final Type<ServerCheck> TYPE = new Type<>(DesktopScreens.id("server_check"));
    public static final StreamCodec<ByteBuf, ServerCheck> STREAM_CODEC = StreamCodec.unit(new ServerCheck());

    /**
     * Set by the client when it starts. Asked when the creative menu is filled, which happens the first time it's
     * opened on each server. A dedicated server never fills it.
     */
    public static volatile BooleanSupplier serverHasMod = () -> true;

    @Override
    public Type<ServerCheck> type() {
        return TYPE;
    }
}
