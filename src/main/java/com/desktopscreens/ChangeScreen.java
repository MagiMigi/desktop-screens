package com.desktopscreens;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
//? if >=1.20.5 {
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?} else {
/*import com.desktopscreens.net.ByteBufCodecs;
import com.desktopscreens.net.StreamCodec;
import com.desktopscreens.net.CustomPacketPayload;
*///?}
import net.minecraft.util.ByIdMap;

/**
 * Client to server, from the Screen Builder: change the screen block {@code screen} belongs to. {@link Action#RESIZE}
 * to the rectangle from {@code from} to {@code to}; {@link Action#MOVE} so the bottom-left of its outline is at
 * {@code from}, facing {@code facing}; or {@link Action#TAKE_DOWN}. The server checks everything again
 * ({@link ScreenBuilder#change}).
 */
public record ChangeScreen(BlockPos screen, Action action, BlockPos from, BlockPos to, Direction facing) implements CustomPacketPayload {
    public enum Action { RESIZE, MOVE, TAKE_DOWN }

    public static final Type<ChangeScreen> TYPE = new Type<>(DesktopScreens.id("change_screen"));
    public static final StreamCodec<ByteBuf, ChangeScreen> STREAM_CODEC = StreamCodec.composite(
            Codecs.BLOCK_POS, ChangeScreen::screen,
            ByteBufCodecs.idMapper(ByIdMap.continuous(Action::ordinal, Action.values(), ByIdMap.OutOfBoundsStrategy.ZERO), Action::ordinal), ChangeScreen::action,
            Codecs.BLOCK_POS, ChangeScreen::from,
            Codecs.BLOCK_POS, ChangeScreen::to,
            Codecs.DIRECTION, ChangeScreen::facing,
            ChangeScreen::new);

    @Override
    public Type<ChangeScreen> type() {
        return TYPE;
    }
}
