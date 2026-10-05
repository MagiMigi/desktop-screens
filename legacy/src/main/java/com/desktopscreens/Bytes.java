package com.desktopscreens;

import io.netty.buffer.ByteBuf;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Writing and reading the pieces our messages are made of, the same way everywhere, with a limit on every length a
 * message names (a broken or hostile one can't make us allocate much). On the newer versions, Minecraft's stream
 * codecs do this.
 */
public final class Bytes {
    private Bytes() {}

    public static void writeVarInt(ByteBuf buf, int value) {
        ByteBufUtils.writeVarInt(buf, value, 5);
    }

    public static int readVarInt(ByteBuf buf) {
        return ByteBufUtils.readVarInt(buf, 5);
    }

    public static void writePos(ByteBuf buf, BlockPos pos) {
        buf.writeLong(pos.toLong());
    }

    public static BlockPos readPos(ByteBuf buf) {
        return BlockPos.fromLong(buf.readLong());
    }

    public static void writeUuid(ByteBuf buf, UUID id) {
        buf.writeLong(id.getMostSignificantBits());
        buf.writeLong(id.getLeastSignificantBits());
    }

    public static UUID readUuid(ByteBuf buf) {
        return new UUID(buf.readLong(), buf.readLong());
    }

    public static void writeUuids(ByteBuf buf, List<UUID> ids) {
        writeVarInt(buf, ids.size());
        for (UUID id : ids) writeUuid(buf, id);
    }

    public static List<UUID> readUuids(ByteBuf buf, int max) {
        int n = count(buf, max);
        List<UUID> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ids.add(readUuid(buf));
        return Collections.unmodifiableList(ids);
    }

    public static void writeString(ByteBuf buf, String text) {
        ByteBufUtils.writeUTF8String(buf, text);
    }

    public static String readString(ByteBuf buf, int maxChars) {
        String text = ByteBufUtils.readUTF8String(buf);
        if (text.length() > maxChars) throw new IllegalArgumentException("text too long: " + text.length());
        return text;
    }

    public static void writeStrings(ByteBuf buf, List<String> texts) {
        writeVarInt(buf, texts.size());
        for (String t : texts) writeString(buf, t);
    }

    public static List<String> readStrings(ByteBuf buf, int max, int maxChars) {
        int n = count(buf, max);
        List<String> texts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) texts.add(readString(buf, maxChars));
        return Collections.unmodifiableList(texts);
    }

    public static void writeBytes(ByteBuf buf, byte[] bytes) {
        writeVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    public static byte[] readBytes(ByteBuf buf, int max) {
        int n = count(buf, max);
        if (n > buf.readableBytes()) throw new IllegalArgumentException("message cut short");
        byte[] bytes = new byte[n];
        buf.readBytes(bytes);
        return bytes;
    }

    private static int count(ByteBuf buf, int max) {
        int n = readVarInt(buf);
        if (n < 0 || n > max) throw new IllegalArgumentException("too many: " + n + " (at most " + max + ")");
        return n;
    }
}
