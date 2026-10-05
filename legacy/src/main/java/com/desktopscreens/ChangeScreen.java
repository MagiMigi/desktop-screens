package com.desktopscreens;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Client to server, from the Screen Builder: change the screen block {@code screen} belongs to. {@link Action#RESIZE}
 * to the rectangle from {@code from} to {@code to}; {@link Action#MOVE} so the bottom-left of its outline is at
 * {@code from}, facing {@code facing}; or {@link Action#TAKE_DOWN}. The server checks everything again
 * ({@link ScreenBuilder#change}).
 */
public final class ChangeScreen implements IMessage {
    public enum Action { RESIZE, MOVE, TAKE_DOWN }

    BlockPos screen, from, to;
    Action action;
    EnumFacing facing;

    /** For the network: filled in by {@link #fromBytes}. */
    public ChangeScreen() {}

    public ChangeScreen(BlockPos screen, Action action, BlockPos from, BlockPos to, EnumFacing facing) {
        this.screen = screen;
        this.action = action;
        this.from = from;
        this.to = to;
        this.facing = facing;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        screen = BlockPos.fromLong(buf.readLong());
        int a = buf.readByte();
        action = a >= 0 && a < Action.values().length ? Action.values()[a] : Action.RESIZE;
        from = BlockPos.fromLong(buf.readLong());
        to = BlockPos.fromLong(buf.readLong());
        facing = EnumFacing.byIndex(buf.readByte());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(screen.toLong());
        buf.writeByte(action.ordinal());
        buf.writeLong(from.toLong());
        buf.writeLong(to.toLong());
        buf.writeByte(facing.getIndex());
    }

    /** On the server: on its own thread. */
    public static final class Handler implements IMessageHandler<ChangeScreen, IMessage> {
        @Override
        public IMessage onMessage(ChangeScreen request, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> ScreenBuilder.change(player, request));
            return null;
        }
    }
}
