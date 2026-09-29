package com.desktopscreens;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;
import java.util.UUID;

/**
 * The messages that carry a shared screen's picture and sound: from the owner's game to the server, and from there to
 * the players watching. Each hop sends the next picture only once the last one has arrived, so a slow connection gets
 * fewer pictures instead of a growing delay. The sound, being small, simply flows. The server side is {@link ShareRelay}.
 *
 * <p>Each screen shows its own thing (since 2026-09-26), so an owner sends a stream of pictures per thing shown on a
 * watched screen: a number the owner's game picks, and with each picture the screens it's for (their ids,
 * {@link ScreenBlockEntity#screenId}; never what they show). Screens showing the same thing share a stream.
 */
public final class ShareStream {
    /** The id of screens turned on before screens had ids: their owner's game shows the tablet's choice on them. */
    public static final UUID NO_ID = new UUID(0L, 0L);
    /** At most this many screens per message. */
    public static final int MAX_SCREENS = 64;
    private static final StreamCodec<ByteBuf, List<UUID>> SCREENS = UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_SCREENS));

    /** A screen's id for sharing: {@link #NO_ID} for one without. */
    public static UUID idOf(UUID screenId) {
        return screenId != null ? screenId : NO_ID;
    }

    /** Players may send the server at most 32767 bytes per message, so a picture goes up in pieces this big. */
    public static final int CHUNK_BYTES = 30000;
    /** The server may send players up to 1 MiB per message: one whole picture, with room to spare. */
    public static final int MAX_FRAME_BYTES = 1000000;
    /** 1 MB in pieces of 30 KB. */
    public static final int MAX_CHUNKS = (MAX_FRAME_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES;

    /** What the client does with the server's messages. Set when the client starts; null on a dedicated server. */
    public interface Client {
        void status(Status status);

        void taken(Taken taken);

        void frame(Frame frame);

        void lists(ScreenSharing.Lists lists);

        void keyframe(Keyframe keyframe);

        void sound(Sound sound);

        void end(End end);
    }

    public static volatile Client client;

    /** The most strips one picture may come in. */
    public static final int MAX_STRIPS = 16;
    /** The first byte of an H.264 picture (a JPEG picture starts with its strip count, 1 to {@link #MAX_STRIPS}). */
    private static final byte H264 = (byte) 0x80;
    private static final int H264_HEADER = 6;

    private ShareStream() {}

    /**
     * An H.264 picture: the marker, a byte with bit 0 set for a keyframe, its width and height (2 bytes each; the
     * decoder rounds its own up to whole 16-row blocks), then the frame, Annex B.
     */
    public static byte[] packH264(byte[] frame, boolean keyframe, int width, int height) {
        java.nio.ByteBuffer out = java.nio.ByteBuffer.allocate(H264_HEADER + frame.length);
        out.put(H264).put((byte) (keyframe ? 1 : 0)).putShort((short) width).putShort((short) height).put(frame);
        return out.array();
    }

    public static boolean isH264(byte[] picture) {
        return picture.length > H264_HEADER && picture[0] == H264;
    }

    public static boolean isKeyframe(byte[] picture) {
        return isH264(picture) && (picture[1] & 1) != 0;
    }

    /** An H.264 picture's width and height. */
    public static int[] h264Size(byte[] picture) {
        java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(picture);
        return new int[] {in.getShort(2) & 0xFFFF, in.getShort(4) & 0xFFFF};
    }

    /** An H.264 picture's frame, Annex B. */
    public static byte[] h264Frame(byte[] picture) {
        return java.util.Arrays.copyOfRange(picture, H264_HEADER, picture.length);
    }

    /**
     * A picture is a few JPEGs, horizontal strips from top to bottom, so several threads can compress it at once:
     * one byte with their number, each one's length (4 bytes), then the JPEGs. The server passes it on untouched.
     */
    public static byte[] pack(List<byte[]> strips) {
        int total = 1 + 4 * strips.size();
        for (byte[] s : strips) total += s.length;
        java.nio.ByteBuffer out = java.nio.ByteBuffer.allocate(total);
        out.put((byte) strips.size());
        for (byte[] s : strips) out.putInt(s.length);
        for (byte[] s : strips) out.put(s);
        return out.array();
    }

    /** The strips of a picture made by {@link #pack}, as {offset, length} into it; null if it isn't one. */
    public static int[][] unpack(byte[] picture) {
        if (picture.length < 1) return null;
        int count = picture[0] & 0xFF;
        if (count < 1 || count > MAX_STRIPS || picture.length < 1 + 4 * count) return null;
        java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(picture);
        in.position(1);
        int[][] strips = new int[count][];
        int at = 1 + 4 * count;
        for (int i = 0; i < count; i++) {
            int length = in.getInt();
            if (length < 1 || at + length > picture.length) return null;
            strips[i] = new int[] {at, length};
            at += length;
        }
        return strips;
    }

    /**
     * Watcher to server, about the shared screen at {@code screen}, sent again every few seconds: whether I want its
     * pictures (it's in sight), its sound (I'm near it), neither (I stopped), and whether my game can decode H.264
     * (Windows' own decoder).
     */
    public record Watch(BlockPos screen, boolean pictures, boolean sound, boolean h264) implements CustomPacketPayload {
        public static final Type<Watch> TYPE = new Type<>(DesktopScreens.id("share_watch"));
        public static final StreamCodec<ByteBuf, Watch> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Watch::screen, ByteBufCodecs.BOOL, Watch::pictures, ByteBufCodecs.BOOL, Watch::sound,
                ByteBufCodecs.BOOL, Watch::h264, Watch::new);

        public boolean stopped() {
            return !pictures && !sound;
        }

        @Override
        public Type<Watch> type() {
            return TYPE;
        }
    }

    /** How much the server allows a sharing player: pictures a second, their size, and how many streams at once. */
    public record Limits(int maxFps, int maxWidth, int maxHeight, int maxStreams) {
        public static final StreamCodec<ByteBuf, Limits> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Limits::maxFps,
                ByteBufCodecs.VAR_INT, Limits::maxWidth,
                ByteBufCodecs.VAR_INT, Limits::maxHeight,
                ByteBufCodecs.VAR_INT, Limits::maxStreams,
                Limits::new);
    }

    /** A screen someone has in sight, and whether everyone who has can decode H.264 (else its pictures go as JPEG). */
    public record Wanted(UUID screen, boolean h264) {
        public static final StreamCodec<ByteBuf, Wanted> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Wanted::screen, ByteBufCodecs.BOOL, Wanted::h264, Wanted::new);
    }

    /**
     * Server to owner: who's watching or listening now (nobody: stop sending), how much the server allows, which of
     * the owner's screens someone has in sight ({@code screens}: send their pictures) and which someone hears
     * ({@code soundScreens}: send their sound; each listener hears one screen per owner, the nearest), and in
     * {@code flags}: whether anyone wants pictures, and whether anyone wants the sound.
     */
    public record Status(List<String> watchers, Limits limits, int flags, List<Wanted> screens, List<UUID> soundScreens)
            implements CustomPacketPayload {
        public static final int PICTURES = 2, SOUND = 4;
        public static final Type<Status> TYPE = new Type<>(DesktopScreens.id("share_status"));
        public static final StreamCodec<ByteBuf, Status> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(ShareScreen.MAX_NAME).apply(ByteBufCodecs.list(64)), Status::watchers,
                Limits.STREAM_CODEC, Status::limits,
                ByteBufCodecs.VAR_INT, Status::flags,
                Wanted.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_SCREENS)), Status::screens,
                SCREENS, Status::soundScreens,
                Status::new);

        public boolean pictures() {
            return (flags & PICTURES) != 0;
        }

        public boolean sound() {
            return (flags & SOUND) != 0;
        }

        @Override
        public Type<Status> type() {
            return TYPE;
        }
    }

    /** One Opus packet: 20 ms of sound, 96 kbit/s stereo, so about 240 bytes; this is plenty. */
    public static final int MAX_SOUND_BYTES = 4000;

    /**
     * Owner to server: packet number {@code seq} of a sound stream (one per program heard: a window's program, or
     * everything for a whole monitor; the label names the screens it's for), one Opus packet. It goes on to everyone
     * who hears one of those screens right away, with no receipts: it's small, and late sound is no use. The numbers
     * skip where the owner's PC was silent.
     */
    public record SoundUp(Label label, int seq, long played, byte[] opus) implements CustomPacketPayload {
        public static final Type<SoundUp> TYPE = new Type<>(DesktopScreens.id("share_sound_up"));
        public static final StreamCodec<ByteBuf, SoundUp> STREAM_CODEC = StreamCodec.composite(
                Label.STREAM_CODEC, SoundUp::label, ByteBufCodecs.VAR_INT, SoundUp::seq, ByteBufCodecs.VAR_LONG, SoundUp::played,
                ByteBufCodecs.byteArray(MAX_SOUND_BYTES), SoundUp::opus, SoundUp::new);

        @Override
        public Type<SoundUp> type() {
            return TYPE;
        }
    }

    /**
     * Server to listener: packet {@code seq} of {@code owner}'s sound stream {@code stream}, for {@code screens} (the
     * ones this listener hears), which played at {@code played} (their clock).
     */
    public record Sound(UUID owner, int stream, List<UUID> screens, int seq, long played, byte[] opus) implements CustomPacketPayload {
        public static final Type<Sound> TYPE = new Type<>(DesktopScreens.id("share_sound"));
        public static final StreamCodec<ByteBuf, Sound> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Sound::owner, ByteBufCodecs.VAR_INT, Sound::stream, SCREENS, Sound::screens,
                ByteBufCodecs.VAR_INT, Sound::seq, ByteBufCodecs.VAR_LONG, Sound::played,
                ByteBufCodecs.byteArray(MAX_SOUND_BYTES), Sound::opus, Sound::new);

        @Override
        public Type<Sound> type() {
            return TYPE;
        }
    }

    /**
     * Server to owner: send a picture of {@code screen} next, a keyframe if it's H.264, even if nothing changed. H.264
     * builds each picture on the ones before, so someone who starts watching, or who fell behind and missed some, can
     * only start again at a keyframe; and someone who starts watching a still desktop would otherwise wait for it to
     * change.
     */
    public record Keyframe(UUID screen) implements CustomPacketPayload {
        public static final Type<Keyframe> TYPE = new Type<>(DesktopScreens.id("share_keyframe"));
        public static final StreamCodec<ByteBuf, Keyframe> STREAM_CODEC = UUIDUtil.STREAM_CODEC.map(Keyframe::new, Keyframe::screen);

        @Override
        public Type<Keyframe> type() {
            return TYPE;
        }
    }

    /**
     * Owner to server: stream {@code stream} ended (its screens show something else now, or aren't watched, or went
     * over the server's limit of streams). Without it, a watcher would keep its last picture up, frozen.
     */
    public record EndUp(int stream) implements CustomPacketPayload {
        public static final Type<EndUp> TYPE = new Type<>(DesktopScreens.id("share_end_up"));
        public static final StreamCodec<ByteBuf, EndUp> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(EndUp::new, EndUp::stream);

        @Override
        public Type<EndUp> type() {
            return TYPE;
        }
    }

    /** Server to watcher: {@code owner}'s stream {@code stream} ended; screens showing it go dark, unless another takes over. */
    public record End(UUID owner, int stream) implements CustomPacketPayload {
        public static final Type<End> TYPE = new Type<>(DesktopScreens.id("share_end"));
        public static final StreamCodec<ByteBuf, End> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, End::owner, ByteBufCodecs.VAR_INT, End::stream, End::new);

        @Override
        public Type<End> type() {
            return TYPE;
        }
    }

    /** Which of the owner's streams a picture belongs to, and the screens it's for. */
    public record Label(int stream, List<UUID> screens) {
        public static final StreamCodec<ByteBuf, Label> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Label::stream, SCREENS, Label::screens, Label::new);
    }

    /**
     * Owner to server: piece {@code index} of {@code count} of picture number {@code frame} of a stream, taken at
     * {@code taken}. The times in these messages are the owner's {@link System#nanoTime}: the watcher compares them
     * with the sound's, which come from the same clock, to measure how far the two are apart (and, with both games on
     * one PC, how late each arrives).
     */
    public record Chunk(Label label, int frame, int index, int count, long taken, byte[] data) implements CustomPacketPayload {
        public static final Type<Chunk> TYPE = new Type<>(DesktopScreens.id("share_chunk"));
        public static final StreamCodec<ByteBuf, Chunk> STREAM_CODEC = StreamCodec.composite(
                Label.STREAM_CODEC, Chunk::label,
                ByteBufCodecs.VAR_INT, Chunk::frame,
                ByteBufCodecs.VAR_INT, Chunk::index,
                ByteBufCodecs.VAR_INT, Chunk::count,
                ByteBufCodecs.VAR_LONG, Chunk::taken,
                ByteBufCodecs.byteArray(CHUNK_BYTES), Chunk::data,
                Chunk::new);

        @Override
        public Type<Chunk> type() {
            return TYPE;
        }
    }

    /** Server to owner: picture {@code frame} of {@code stream} has arrived whole; send the next one when there is one. */
    public record Taken(int stream, int frame) implements CustomPacketPayload {
        public static final Type<Taken> TYPE = new Type<>(DesktopScreens.id("share_taken"));
        public static final StreamCodec<ByteBuf, Taken> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Taken::stream, ByteBufCodecs.VAR_INT, Taken::frame, Taken::new);

        @Override
        public Type<Taken> type() {
            return TYPE;
        }
    }

    /**
     * Server to watcher: {@code owner}'s picture number {@code frame} of {@code stream}, taken at {@code taken} (the
     * owner's clock), for {@code screens}: the ones this watcher has in sight.
     */
    public record Frame(UUID owner, int stream, int frame, long taken, List<UUID> screens, byte[] picture) implements CustomPacketPayload {
        public static final Type<Frame> TYPE = new Type<>(DesktopScreens.id("share_frame"));
        public static final StreamCodec<ByteBuf, Frame> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Frame::owner,
                ByteBufCodecs.VAR_INT, Frame::stream,
                ByteBufCodecs.VAR_INT, Frame::frame,
                ByteBufCodecs.VAR_LONG, Frame::taken,
                SCREENS, Frame::screens,
                ByteBufCodecs.byteArray(MAX_FRAME_BYTES), Frame::picture,
                Frame::new);

        @Override
        public Type<Frame> type() {
            return TYPE;
        }
    }

    /** Watcher to server: {@code owner}'s picture {@code frame} of {@code stream} arrived; the next one may come. */
    public record Received(UUID owner, int stream, int frame) implements CustomPacketPayload {
        public static final Type<Received> TYPE = new Type<>(DesktopScreens.id("share_received"));
        public static final StreamCodec<ByteBuf, Received> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Received::owner, ByteBufCodecs.VAR_INT, Received::stream,
                ByteBufCodecs.VAR_INT, Received::frame, Received::new);

        @Override
        public Type<Received> type() {
            return TYPE;
        }
    }
}
