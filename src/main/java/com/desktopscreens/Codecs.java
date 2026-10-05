package com.desktopscreens;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
//? if >=1.20.5 {
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
//?} else {
/*import com.desktopscreens.net.ByteBufCodecs;
import com.desktopscreens.net.StreamCodec;
import net.minecraft.network.FriendlyByteBuf;
*///?}

import java.util.UUID;

/** How Minecraft's own types go into our messages. From 1.20.5 on Minecraft has these; before, they're written here. */
final class Codecs {
    //? if >=1.20.5 {
    static final StreamCodec<ByteBuf, BlockPos> BLOCK_POS = BlockPos.STREAM_CODEC;
    static final StreamCodec<ByteBuf, Direction> DIRECTION = Direction.STREAM_CODEC;
    static final StreamCodec<ByteBuf, UUID> UUID = UUIDUtil.STREAM_CODEC;
    //?} else {
    /*static final StreamCodec<ByteBuf, BlockPos> BLOCK_POS = StreamCodec.of(
            (buf, pos) -> buf.writeLong(pos.asLong()), buf -> BlockPos.of(buf.readLong()));
    static final StreamCodec<ByteBuf, Direction> DIRECTION = ByteBufCodecs.idMapper(
            Direction::from3DDataValue, Direction::get3DDataValue);
    static final StreamCodec<ByteBuf, UUID> UUID = StreamCodec.of(
            (buf, id) -> new FriendlyByteBuf(buf).writeUUID(id), buf -> new FriendlyByteBuf(buf).readUUID());
    *///?}

    private Codecs() {}
}
