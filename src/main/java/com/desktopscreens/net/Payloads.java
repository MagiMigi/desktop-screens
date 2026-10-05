package com.desktopscreens.net;

import com.desktopscreens.BuildScreen;
import com.desktopscreens.BuilderRules;
import com.desktopscreens.ChangeScreen;
import com.desktopscreens.ScreenBuilder;
import com.desktopscreens.ScreenSharing;
import com.desktopscreens.ServerCheck;
import com.desktopscreens.ShareRelay;
import com.desktopscreens.ShareScreen;
import com.desktopscreens.ShareStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * For Minecraft before 1.20.5: every message of ours, what reads it and what handles it, in one list both loaders
 * register (on later versions each loader registers them itself). A message travels as a custom payload packet: the
 * type's id and the bytes its codec writes.
 */
public final class Payloads {
    /** Player to server. The loader reads it on the network thread and hands it to the server's thread. */
    public record ToServer<T extends CustomPacketPayload>(
            CustomPacketPayload.Type<T> type, StreamCodec<ByteBuf, T> codec, BiConsumer<ServerPlayer, T> handler) {
        /** Reads a message from {@code buf}; returns what handles it, to be run on the server's thread. */
        public Runnable read(ServerPlayer player, FriendlyByteBuf buf) {
            T payload = codec.decode(buf);
            return () -> handler.accept(player, payload);
        }
    }

    /**
     * Server to player. The loader reads it on the network thread and hands it to the game's thread, unless
     * {@code networkThread}: shared sound goes straight to its own thread, since a game in the background may take
     * packets only about once a second (the mixin on {@code ClientboundCustomPayloadPacket} does this on later versions).
     */
    public record ToClient<T extends CustomPacketPayload>(
            CustomPacketPayload.Type<T> type, StreamCodec<ByteBuf, T> codec, Consumer<T> handler, boolean networkThread) {
        /** Reads a message from {@code buf}; returns what handles it. */
        public Runnable read(FriendlyByteBuf buf) {
            T payload = codec.decode(buf);
            return () -> handler.accept(payload);
        }
    }

    public static final List<ToServer<?>> TO_SERVER = List.of(
            new ToServer<>(ServerCheck.TYPE, ServerCheck.STREAM_CODEC, (player, payload) -> {}),
            new ToServer<>(BuildScreen.TYPE, BuildScreen.STREAM_CODEC, ScreenBuilder::build),
            new ToServer<>(ChangeScreen.TYPE, ChangeScreen.STREAM_CODEC, ScreenBuilder::change),
            new ToServer<>(ShareScreen.TYPE, ShareScreen.STREAM_CODEC, ScreenSharing::change),
            new ToServer<>(ShareStream.Watch.TYPE, ShareStream.Watch.STREAM_CODEC, ShareRelay::watch),
            new ToServer<>(ShareStream.Chunk.TYPE, ShareStream.Chunk.STREAM_CODEC, ShareRelay::chunk),
            new ToServer<>(ShareStream.Received.TYPE, ShareStream.Received.STREAM_CODEC, ShareRelay::received),
            new ToServer<>(ShareStream.SoundUp.TYPE, ShareStream.SoundUp.STREAM_CODEC, ShareRelay::sound),
            new ToServer<>(ShareStream.EndUp.TYPE, ShareStream.EndUp.STREAM_CODEC, ShareRelay::end));

    // Client side through ShareStream.client, so a dedicated server never loads client code.
    public static final List<ToClient<?>> TO_CLIENT = List.of(
            new ToClient<>(ShareStream.Status.TYPE, ShareStream.Status.STREAM_CODEC, payload -> {
                if (ShareStream.client != null) ShareStream.client.status(payload);
            }, false),
            new ToClient<>(ShareStream.Taken.TYPE, ShareStream.Taken.STREAM_CODEC, payload -> {
                if (ShareStream.client != null) ShareStream.client.taken(payload);
            }, false),
            new ToClient<>(ShareStream.Frame.TYPE, ShareStream.Frame.STREAM_CODEC, payload -> {
                if (ShareStream.client != null) ShareStream.client.frame(payload);
            }, false),
            new ToClient<>(ScreenSharing.Lists.TYPE, ScreenSharing.Lists.STREAM_CODEC, payload -> {
                if (ShareStream.client != null) ShareStream.client.lists(payload);
            }, false),
            new ToClient<>(ShareStream.Keyframe.TYPE, ShareStream.Keyframe.STREAM_CODEC, payload -> {
                if (ShareStream.client != null) ShareStream.client.keyframe(payload);
            }, false),
            new ToClient<>(ShareStream.Sound.TYPE, ShareStream.Sound.STREAM_CODEC, payload -> {
                if (ShareStream.client != null) ShareStream.client.sound(payload);
            }, true),
            new ToClient<>(ShareStream.End.TYPE, ShareStream.End.STREAM_CODEC, payload -> {
                if (ShareStream.client != null) ShareStream.client.end(payload);
            }, false),
            new ToClient<>(BuilderRules.TYPE, BuilderRules.STREAM_CODEC, BuilderRules::received, false));

    private static final Map<ResourceLocation, StreamCodec<ByteBuf, ?>> CODECS = new HashMap<>();

    static {
        for (ToServer<?> m : TO_SERVER) CODECS.put(m.type().id(), m.codec());
        for (ToClient<?> m : TO_CLIENT) CODECS.put(m.type().id(), m.codec());
    }

    private Payloads() {}

    /** A message's bytes, for a custom payload packet with its type's id. */
    @SuppressWarnings("unchecked")
    public static FriendlyByteBuf encode(CustomPacketPayload payload) {
        StreamCodec<ByteBuf, CustomPacketPayload> codec = (StreamCodec<ByteBuf, CustomPacketPayload>) CODECS.get(payload.type().id());
        if (codec == null) throw new IllegalArgumentException("Not one of our messages: " + payload.type().id());
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        codec.encode(buf, payload);
        return buf;
    }
}
