package com.desktopscreens;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: the owner of the screen at {@code screen} shares it with the player called {@code name}, or stops
 * sharing it with them; with {@code allScreens}, all their screens instead (the screen is then only where they did it).
 * The server checks it ({@link ScreenSharing#change}).
 */
public record ShareScreen(BlockPos screen, String name, boolean add, boolean allScreens) implements CustomPacketPayload {
    public static final Type<ShareScreen> TYPE = new Type<>(DesktopScreens.id("share_screen"));
    /** Minecraft names are at most 16 characters. */
    public static final int MAX_NAME = 16;
    public static final StreamCodec<ByteBuf, ShareScreen> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ShareScreen::screen,
            ByteBufCodecs.stringUtf8(MAX_NAME), ShareScreen::name,
            ByteBufCodecs.BOOL, ShareScreen::add,
            ByteBufCodecs.BOOL, ShareScreen::allScreens,
            ShareScreen::new);

    @Override
    public Type<ShareScreen> type() {
        return TYPE;
    }
}
