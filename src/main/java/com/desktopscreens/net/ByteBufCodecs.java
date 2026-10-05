package com.desktopscreens.net;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/**
 * For Minecraft before 1.20.5: the codecs of its later {@code ByteBufCodecs} our messages use, with the same names,
 * written the same way on the wire (through that version's {@link FriendlyByteBuf}).
 */
public final class ByteBufCodecs {
    public static final StreamCodec<ByteBuf, Integer> VAR_INT = StreamCodec.of(
            (buf, v) -> friendly(buf).writeVarInt(v), buf -> friendly(buf).readVarInt());
    public static final StreamCodec<ByteBuf, Long> VAR_LONG = StreamCodec.of(
            (buf, v) -> friendly(buf).writeVarLong(v), buf -> friendly(buf).readVarLong());
    public static final StreamCodec<ByteBuf, Boolean> BOOL = StreamCodec.of(ByteBuf::writeBoolean, ByteBuf::readBoolean);

    private ByteBufCodecs() {}

    public static StreamCodec<ByteBuf, String> stringUtf8(int maxLength) {
        return StreamCodec.of((buf, v) -> friendly(buf).writeUtf(v, maxLength), buf -> friendly(buf).readUtf(maxLength));
    }

    public static StreamCodec<ByteBuf, byte[]> byteArray(int maxLength) {
        return StreamCodec.of((buf, v) -> {
            if (v.length > maxLength) throw new IllegalArgumentException("Byte array too long: " + v.length + " > " + maxLength);
            friendly(buf).writeByteArray(v);
        }, buf -> friendly(buf).readByteArray(maxLength));
    }

    public static <T> StreamCodec<ByteBuf, T> idMapper(IntFunction<T> byId, ToIntFunction<T> toId) {
        return StreamCodec.of((buf, v) -> friendly(buf).writeVarInt(toId.applyAsInt(v)), buf -> byId.apply(friendly(buf).readVarInt()));
    }

    public static <B extends ByteBuf, V> StreamCodec.CodecOperation<B, V, List<V>> list(int maxSize) {
        return codec -> StreamCodec.of((buf, list) -> {
            if (list.size() > maxSize) throw new IllegalArgumentException("List too long: " + list.size() + " > " + maxSize);
            friendly(buf).writeVarInt(list.size());
            for (V v : list) codec.encode(buf, v);
        }, buf -> {
            int size = friendly(buf).readVarInt();
            if (size < 0 || size > maxSize) throw new DecoderException("List too long: " + size + " > " + maxSize);
            List<V> list = new ArrayList<>(Math.min(size, 64));
            for (int i = 0; i < size; i++) list.add(codec.decode(buf));
            return list;
        });
    }

    static FriendlyByteBuf friendly(ByteBuf buf) {
        return buf instanceof FriendlyByteBuf f ? f : new FriendlyByteBuf(buf);
    }
}
