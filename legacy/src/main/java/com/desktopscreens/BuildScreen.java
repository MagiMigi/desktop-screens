package com.desktopscreens;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Client to server: build a screen from corner {@code from} to corner {@code to}, its picture facing {@code facing}.
 * The server checks everything again ({@link ScreenBuilder#build}).
 */
public final class BuildScreen implements IMessage {
    BlockPos from, to;
    EnumFacing facing;

    /** For the network: filled in by {@link #fromBytes}. */
    public BuildScreen() {}

    public BuildScreen(BlockPos from, BlockPos to, EnumFacing facing) {
        this.from = from;
        this.to = to;
        this.facing = facing;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        from = BlockPos.fromLong(buf.readLong());
        to = BlockPos.fromLong(buf.readLong());
        facing = EnumFacing.byIndex(buf.readByte());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(from.toLong());
        buf.writeLong(to.toLong());
        buf.writeByte(facing.getIndex());
    }

    /** On the server: on its own thread. */
    public static final class Handler implements IMessageHandler<BuildScreen, IMessage> {
        @Override
        public IMessage onMessage(BuildScreen request, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> ScreenBuilder.build(player, request));
            return null;
        }
    }
}
