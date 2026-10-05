package com.desktopscreens;

import io.netty.buffer.Unpooled;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.CPacketCustomPayload;
import net.minecraft.network.play.server.SPacketCustomPayload;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/**
 * The mod's messages between client and server, on one channel. A game or server without the mod simply never hears
 * of them (the mod accepts any other side, see {@link DesktopScreens}).
 */
public final class Net {
    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(DesktopScreens.MOD_ID);
    /** The shared sound's message ids, which {@link #toServerNow} and {@link #toPlayerNow} write themselves. */
    public static final int SOUND_UP = 14, SOUND = 15;

    private Net() {}

    /** Once, when the mod is set up. The numbers are the messages' ids on the channel: never reuse one. */
    static void register() {
        CHANNEL.registerMessage(BuildScreen.Handler.class, BuildScreen.class, 0, Side.SERVER);
        CHANNEL.registerMessage(ChangeScreen.Handler.class, ChangeScreen.class, 1, Side.SERVER);
        CHANNEL.registerMessage(BuilderRules.Handler.class, BuilderRules.class, 2, Side.CLIENT);
        // Sharing a screen.
        CHANNEL.registerMessage(ShareScreen.Handler.class, ShareScreen.class, 3, Side.SERVER);
        CHANNEL.registerMessage(ScreenSharing.Lists.Handler.class, ScreenSharing.Lists.class, 4, Side.CLIENT);
        CHANNEL.registerMessage(ShareStream.Watch.Handler.class, ShareStream.Watch.class, 5, Side.SERVER);
        CHANNEL.registerMessage(ShareStream.Status.Handler.class, ShareStream.Status.class, 6, Side.CLIENT);
        CHANNEL.registerMessage(ShareStream.Chunk.Handler.class, ShareStream.Chunk.class, 7, Side.SERVER);
        CHANNEL.registerMessage(ShareStream.Taken.Handler.class, ShareStream.Taken.class, 8, Side.CLIENT);
        CHANNEL.registerMessage(ShareStream.Frame.Handler.class, ShareStream.Frame.class, 9, Side.CLIENT);
        CHANNEL.registerMessage(ShareStream.Received.Handler.class, ShareStream.Received.class, 10, Side.SERVER);
        CHANNEL.registerMessage(ShareStream.Keyframe.Handler.class, ShareStream.Keyframe.class, 11, Side.CLIENT);
        CHANNEL.registerMessage(ShareStream.EndUp.Handler.class, ShareStream.EndUp.class, 12, Side.SERVER);
        CHANNEL.registerMessage(ShareStream.End.Handler.class, ShareStream.End.class, 13, Side.CLIENT);
        CHANNEL.registerMessage(ShareStream.SoundUp.Handler.class, ShareStream.SoundUp.class, SOUND_UP, Side.SERVER);
        CHANNEL.registerMessage(ShareStream.Sound.Handler.class, ShareStream.Sound.class, SOUND, Side.CLIENT);
    }

    /**
     * A message on our channel, sent at once from any thread as Minecraft's own custom payload packet, which is what
     * the channel would send: its id, then the message. Forge 1.12.2's channel itself must only be sent on from one
     * thread (it sets the target, then writes), so the game's or the server's; the shared sound can't wait for that
     * (the server's thread runs what came in once a tick, 50 ms or more).
     */
    private static PacketBuffer payload(int id, IMessage message) {
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.writeByte(id);
        message.toBytes(buf);
        return buf;
    }

    /** See {@link #payload}. Client side: the connection's {@code NetworkManager} (the client's handler only exists there). */
    public static void toServerNow(NetworkManager connection, int id, IMessage message) {
        connection.sendPacket(new CPacketCustomPayload(DesktopScreens.MOD_ID, payload(id, message)));
    }

    /** See {@link #payload}. Server side. */
    static void toPlayerNow(EntityPlayerMP player, int id, IMessage message) {
        player.connection.sendPacket(new SPacketCustomPayload(DesktopScreens.MOD_ID, payload(id, message)));
    }
}
