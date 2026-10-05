package com.desktopscreens;

import io.netty.buffer.ByteBuf;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Client to server: the owner of the screen at {@code screen} shares it with the player called {@code name}, or stops
 * sharing it with them; with {@code allScreens}, all their screens instead (the screen is then only where they did it).
 * The server checks it ({@link ScreenSharing#change}).
 */
public final class ShareScreen implements IMessage {
    /** Minecraft names are at most 16 characters. */
    public static final int MAX_NAME = 16;

    private BlockPos screen;
    private String name;
    private boolean add, allScreens;

    public ShareScreen() {}

    public ShareScreen(BlockPos screen, String name, boolean add, boolean allScreens) {
        this.screen = screen;
        this.name = name;
        this.add = add;
        this.allScreens = allScreens;
    }

    public BlockPos screen() { return screen; }
    public String name() { return name; }
    public boolean add() { return add; }
    public boolean allScreens() { return allScreens; }

    @Override
    public void fromBytes(ByteBuf buf) {
        screen = Bytes.readPos(buf);
        name = Bytes.readString(buf, MAX_NAME);
        add = buf.readBoolean();
        allScreens = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        Bytes.writePos(buf, screen);
        Bytes.writeString(buf, name);
        buf.writeBoolean(add);
        buf.writeBoolean(allScreens);
    }

    public static final class Handler implements IMessageHandler<ShareScreen, IMessage> {
        @Override
        public IMessage onMessage(ShareScreen m, MessageContext c) {
            return ShareStream.onServer(ScreenSharing::change).onMessage(m, c);
        }
    }
}
