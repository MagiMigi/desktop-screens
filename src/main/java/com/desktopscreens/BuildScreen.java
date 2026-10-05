package com.desktopscreens;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
//? if >=1.20.5 {
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?} else {
/*import com.desktopscreens.net.StreamCodec;
import com.desktopscreens.net.CustomPacketPayload;
*///?}

/**
 * Client to server: build a screen from corner {@code from} to corner {@code to}, its picture facing {@code facing}.
 * The server checks everything again ({@link ScreenBuilder#build}).
 */
public record BuildScreen(BlockPos from, BlockPos to, Direction facing) implements CustomPacketPayload {
    public static final Type<BuildScreen> TYPE = new Type<>(DesktopScreens.id("build_screen"));
    public static final StreamCodec<ByteBuf, BuildScreen> STREAM_CODEC = StreamCodec.composite(
            Codecs.BLOCK_POS, BuildScreen::from,
            Codecs.BLOCK_POS, BuildScreen::to,
            Codecs.DIRECTION, BuildScreen::facing,
            BuildScreen::new);

    @Override
    public Type<BuildScreen> type() {
        return TYPE;
    }
}
