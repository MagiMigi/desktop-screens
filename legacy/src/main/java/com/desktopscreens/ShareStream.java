package com.desktopscreens;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The messages that carry a shared screen's picture and sound: from the owner's game to the server, and from the
 * server to the players watching. Each hop sends the next picture only once an earlier one has arrived, so a slow
 * connection gets fewer pictures instead of a growing delay. The sound, being small, simply flows. The server side is
 * {@link ShareRelay}.
 *
 * <p>Each screen shows its own thing, so an owner sends a stream of pictures per thing shown on a watched screen: a
 * number the owner's game picks, and with each picture the screens it's for (their ids, {@link ScreenBlockEntity#screenId};
 * never what they show). Screens showing the same thing share a stream.
 *
 * <p>On 1.12.2 each message is a class with its reading and writing, and the accessors the newer versions' records have.
 * Messages to the server are handled on its thread; messages to the player go to {@link #client} on the network
 * thread, which hands over to the game's thread what needs it.
 */
public final class ShareStream {
    /** The id of screens turned on before screens had ids: their owner's game shows the tablet's choice on them. */
    public static final UUID NO_ID = new UUID(0L, 0L);
    /** At most this many screens per message. */
    public static final int MAX_SCREENS = 64;

    /** A screen's id for sharing: {@link #NO_ID} for one without. */
    public static UUID idOf(UUID screenId) {
        return screenId != null ? screenId : NO_ID;
    }

    /** Players may send the server at most 32767 bytes per message, so a picture goes up in pieces this big. */
    public static final int CHUNK_BYTES = 30000;
    /** The server may send players up to 1 MiB per message (Forge splits bigger ones): one whole picture, with room to spare. */
    public static final int MAX_FRAME_BYTES = 1000000;
    /** 1 MB in pieces of 30 KB. */
    public static final int MAX_CHUNKS = (MAX_FRAME_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES;
    /**
     * How many pictures of a stream may be on their way at once, owner to server and server to each watcher, before
     * the next waits for a receipt. A 1.12.2 server handles what players send once per tick (20 times a second, like
     * 26.1's), so with one a stream couldn't pass 20 pictures a second; three leave room for 30.
     */
    public static final int IN_FLIGHT = 3;

    /** What the client does with the server's messages. Set when the client starts; null on a dedicated server. Network thread. */
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

    /** A handler for messages to the server: on its thread, with the player who sent it. */
    static <M extends IMessage> IMessageHandler<M, IMessage> onServer(BiConsumer<EntityPlayerMP, M> handle) {
        return (message, context) -> {
            EntityPlayerMP player = context.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> handle.accept(player, message));
            return null;
        };
    }

    /** Server to client messages go to {@link #client}, on the network thread. */
    private static Client client() {
        return client;
    }

    /**
     * Watcher to server, about the shared screen at {@code screen}, sent again every few seconds: whether I want its
     * pictures (it's in sight), its sound (I'm near it), neither (I stopped), and whether my game can decode H.264
     * (Windows' own decoder).
     */
    public static final class Watch implements IMessage {
        private BlockPos screen;
        private boolean pictures, sound, h264;

        public Watch() {}

        public Watch(BlockPos screen, boolean pictures, boolean sound, boolean h264) {
            this.screen = screen;
            this.pictures = pictures;
            this.sound = sound;
            this.h264 = h264;
        }

        public BlockPos screen() { return screen; }
        public boolean pictures() { return pictures; }
        public boolean sound() { return sound; }
        public boolean h264() { return h264; }

        public boolean stopped() {
            return !pictures && !sound;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            screen = Bytes.readPos(buf);
            pictures = buf.readBoolean();
            sound = buf.readBoolean();
            h264 = buf.readBoolean();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            Bytes.writePos(buf, screen);
            buf.writeBoolean(pictures);
            buf.writeBoolean(sound);
            buf.writeBoolean(h264);
        }

        public static final class Handler implements IMessageHandler<Watch, IMessage> {
            @Override
            public IMessage onMessage(Watch m, MessageContext c) {
                return onServer(ShareRelay::watch).onMessage(m, c);
            }
        }
    }

    /** How much the server allows a sharing player: pictures a second, their size, and how many streams at once. */
    public static final class Limits {
        private final int maxFps, maxWidth, maxHeight, maxStreams;

        public Limits(int maxFps, int maxWidth, int maxHeight, int maxStreams) {
            this.maxFps = maxFps;
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
            this.maxStreams = maxStreams;
        }

        public int maxFps() { return maxFps; }
        public int maxWidth() { return maxWidth; }
        public int maxHeight() { return maxHeight; }
        public int maxStreams() { return maxStreams; }

        void write(ByteBuf buf) {
            Bytes.writeVarInt(buf, maxFps);
            Bytes.writeVarInt(buf, maxWidth);
            Bytes.writeVarInt(buf, maxHeight);
            Bytes.writeVarInt(buf, maxStreams);
        }

        static Limits read(ByteBuf buf) {
            return new Limits(Bytes.readVarInt(buf), Bytes.readVarInt(buf), Bytes.readVarInt(buf), Bytes.readVarInt(buf));
        }
    }

    /** A screen someone has in sight, and whether everyone who has can decode H.264 (else its pictures go as JPEG). */
    public static final class Wanted {
        private final UUID screen;
        private final boolean h264;

        public Wanted(UUID screen, boolean h264) {
            this.screen = screen;
            this.h264 = h264;
        }

        public UUID screen() { return screen; }
        public boolean h264() { return h264; }
    }

    /**
     * Server to owner: who's watching or listening now (nobody: stop sending), how much the server allows, which of
     * the owner's screens someone has in sight ({@code screens}: send their pictures) and which someone hears
     * ({@code soundScreens}: send their sound; each listener hears one screen per owner, the nearest), and in
     * {@code flags}: whether anyone wants pictures, and whether anyone wants the sound.
     */
    public static final class Status implements IMessage {
        public static final int PICTURES = 2, SOUND = 4;
        private List<String> watchers;
        private Limits limits;
        private int flags;
        private List<Wanted> screens;
        private List<UUID> soundScreens;

        public Status() {}

        public Status(List<String> watchers, Limits limits, int flags, List<Wanted> screens, List<UUID> soundScreens) {
            this.watchers = watchers;
            this.limits = limits;
            this.flags = flags;
            this.screens = screens;
            this.soundScreens = soundScreens;
        }

        public List<String> watchers() { return watchers; }
        public Limits limits() { return limits; }
        public int flags() { return flags; }
        public List<Wanted> screens() { return screens; }
        public List<UUID> soundScreens() { return soundScreens; }

        public boolean pictures() {
            return (flags & PICTURES) != 0;
        }

        public boolean sound() {
            return (flags & SOUND) != 0;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            watchers = Bytes.readStrings(buf, 64, ShareScreen.MAX_NAME);
            limits = Limits.read(buf);
            flags = Bytes.readVarInt(buf);
            int n = Bytes.readVarInt(buf);
            if (n < 0 || n > MAX_SCREENS) throw new IllegalArgumentException("too many screens: " + n);
            List<Wanted> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) list.add(new Wanted(Bytes.readUuid(buf), buf.readBoolean()));
            screens = Collections.unmodifiableList(list);
            soundScreens = Bytes.readUuids(buf, MAX_SCREENS);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            Bytes.writeStrings(buf, watchers);
            limits.write(buf);
            Bytes.writeVarInt(buf, flags);
            Bytes.writeVarInt(buf, screens.size());
            for (Wanted w : screens) {
                Bytes.writeUuid(buf, w.screen);
                buf.writeBoolean(w.h264);
            }
            Bytes.writeUuids(buf, soundScreens);
        }

        public static final class Handler implements IMessageHandler<Status, IMessage> {
            @Override
            public IMessage onMessage(Status m, MessageContext c) {
                if (client() != null) client().status(m);
                return null;
            }
        }
    }

    /** One Opus packet: 20 ms of sound, 96 kbit/s stereo, so about 240 bytes; this is plenty. */
    public static final int MAX_SOUND_BYTES = 4000;

    /** Which of the owner's streams a picture or sound belongs to, and the screens it's for. */
    public static final class Label {
        private final int stream;
        private final List<UUID> screens;

        public Label(int stream, List<UUID> screens) {
            this.stream = stream;
            this.screens = screens;
        }

        public int stream() { return stream; }
        public List<UUID> screens() { return screens; }

        void write(ByteBuf buf) {
            Bytes.writeVarInt(buf, stream);
            Bytes.writeUuids(buf, screens);
        }

        static Label read(ByteBuf buf) {
            return new Label(Bytes.readVarInt(buf), Bytes.readUuids(buf, MAX_SCREENS));
        }
    }

    /**
     * Owner to server: packet number {@code seq} of a sound stream (one per program heard: a window's program, or
     * everything for a whole monitor; the label names the screens it's for), one Opus packet. It goes on to everyone
     * who hears one of those screens right away, with no receipts: it's small, and late sound is no use.
     */
    public static final class SoundUp implements IMessage {
        private Label label;
        private int seq;
        private long played;
        private byte[] opus;

        public SoundUp() {}

        public SoundUp(Label label, int seq, long played, byte[] opus) {
            this.label = label;
            this.seq = seq;
            this.played = played;
            this.opus = opus;
        }

        public Label label() { return label; }
        public int seq() { return seq; }
        public long played() { return played; }
        public byte[] opus() { return opus; }

        @Override
        public void fromBytes(ByteBuf buf) {
            label = Label.read(buf);
            seq = Bytes.readVarInt(buf);
            played = buf.readLong();
            opus = Bytes.readBytes(buf, MAX_SOUND_BYTES);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            label.write(buf);
            Bytes.writeVarInt(buf, seq);
            buf.writeLong(played);
            Bytes.writeBytes(buf, opus);
        }

        public static final class Handler implements IMessageHandler<SoundUp, IMessage> {
            @Override
            public IMessage onMessage(SoundUp m, MessageContext c) {
                // Straight on from the network thread: through the server's, it would wait for the next tick.
                ShareRelay.sound(c.getServerHandler().player, m);
                return null;
            }
        }
    }

    /**
     * Server to listener: packet {@code seq} of {@code owner}'s sound stream {@code stream}, for {@code screens} (the
     * ones this listener hears), which played at {@code played} (their clock).
     */
    public static final class Sound implements IMessage {
        private UUID owner;
        private int stream;
        private List<UUID> screens;
        private int seq;
        private long played;
        private byte[] opus;

        public Sound() {}

        public Sound(UUID owner, int stream, List<UUID> screens, int seq, long played, byte[] opus) {
            this.owner = owner;
            this.stream = stream;
            this.screens = screens;
            this.seq = seq;
            this.played = played;
            this.opus = opus;
        }

        public UUID owner() { return owner; }
        public int stream() { return stream; }
        public List<UUID> screens() { return screens; }
        public int seq() { return seq; }
        public long played() { return played; }
        public byte[] opus() { return opus; }

        @Override
        public void fromBytes(ByteBuf buf) {
            owner = Bytes.readUuid(buf);
            stream = Bytes.readVarInt(buf);
            screens = Bytes.readUuids(buf, MAX_SCREENS);
            seq = Bytes.readVarInt(buf);
            played = buf.readLong();
            opus = Bytes.readBytes(buf, MAX_SOUND_BYTES);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            Bytes.writeUuid(buf, owner);
            Bytes.writeVarInt(buf, stream);
            Bytes.writeUuids(buf, screens);
            Bytes.writeVarInt(buf, seq);
            buf.writeLong(played);
            Bytes.writeBytes(buf, opus);
        }

        public static final class Handler implements IMessageHandler<Sound, IMessage> {
            @Override
            public IMessage onMessage(Sound m, MessageContext c) {
                if (client() != null) client().sound(m);
                return null;
            }
        }
    }

    /**
     * Server to owner: send a picture of {@code screen} next, a keyframe if it's H.264, even if nothing changed. H.264
     * builds each picture on the ones before, so someone who starts watching, or who fell behind and missed some, can
     * only start again at a keyframe; and someone who starts watching a still desktop would otherwise wait for it to
     * change.
     */
    public static final class Keyframe implements IMessage {
        private UUID screen;

        public Keyframe() {}

        public Keyframe(UUID screen) {
            this.screen = screen;
        }

        public UUID screen() { return screen; }

        @Override
        public void fromBytes(ByteBuf buf) {
            screen = Bytes.readUuid(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            Bytes.writeUuid(buf, screen);
        }

        public static final class Handler implements IMessageHandler<Keyframe, IMessage> {
            @Override
            public IMessage onMessage(Keyframe m, MessageContext c) {
                if (client() != null) client().keyframe(m);
                return null;
            }
        }
    }

    /**
     * Owner to server: stream {@code stream} ended (its screens show something else now, or aren't watched, or went
     * over the server's limit of streams). Without it, a watcher would keep its last picture up, frozen.
     */
    public static final class EndUp implements IMessage {
        private int stream;

        public EndUp() {}

        public EndUp(int stream) {
            this.stream = stream;
        }

        public int stream() { return stream; }

        @Override
        public void fromBytes(ByteBuf buf) {
            stream = Bytes.readVarInt(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            Bytes.writeVarInt(buf, stream);
        }

        public static final class Handler implements IMessageHandler<EndUp, IMessage> {
            @Override
            public IMessage onMessage(EndUp m, MessageContext c) {
                return onServer(ShareRelay::end).onMessage(m, c);
            }
        }
    }

    /** Server to watcher: {@code owner}'s stream {@code stream} ended; screens showing it go dark, unless another takes over. */
    public static final class End implements IMessage {
        private UUID owner;
        private int stream;

        public End() {}

        public End(UUID owner, int stream) {
            this.owner = owner;
            this.stream = stream;
        }

        public UUID owner() { return owner; }
        public int stream() { return stream; }

        @Override
        public void fromBytes(ByteBuf buf) {
            owner = Bytes.readUuid(buf);
            stream = Bytes.readVarInt(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            Bytes.writeUuid(buf, owner);
            Bytes.writeVarInt(buf, stream);
        }

        public static final class Handler implements IMessageHandler<End, IMessage> {
            @Override
            public IMessage onMessage(End m, MessageContext c) {
                if (client() != null) client().end(m);
                return null;
            }
        }
    }

    /**
     * Owner to server: piece {@code index} of {@code count} of picture number {@code frame} of a stream, taken at
     * {@code taken}. The times in these messages are the owner's {@link System#nanoTime}: the watcher compares them
     * with the sound's, which come from the same clock, to measure how far the two are apart.
     */
    public static final class Chunk implements IMessage {
        private Label label;
        private int frame, index, count;
        private long taken;
        private byte[] data;

        public Chunk() {}

        public Chunk(Label label, int frame, int index, int count, long taken, byte[] data) {
            this.label = label;
            this.frame = frame;
            this.index = index;
            this.count = count;
            this.taken = taken;
            this.data = data;
        }

        public Label label() { return label; }
        public int frame() { return frame; }
        public int index() { return index; }
        public int count() { return count; }
        public long taken() { return taken; }
        public byte[] data() { return data; }

        @Override
        public void fromBytes(ByteBuf buf) {
            label = Label.read(buf);
            frame = Bytes.readVarInt(buf);
            index = Bytes.readVarInt(buf);
            count = Bytes.readVarInt(buf);
            taken = buf.readLong();
            data = Bytes.readBytes(buf, CHUNK_BYTES);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            label.write(buf);
            Bytes.writeVarInt(buf, frame);
            Bytes.writeVarInt(buf, index);
            Bytes.writeVarInt(buf, count);
            buf.writeLong(taken);
            Bytes.writeBytes(buf, data);
        }

        public static final class Handler implements IMessageHandler<Chunk, IMessage> {
            @Override
            public IMessage onMessage(Chunk m, MessageContext c) {
                return onServer(ShareRelay::chunk).onMessage(m, c);
            }
        }
    }

    /** Server to owner: picture {@code frame} of {@code stream} has arrived whole; send the next one when there is one. */
    public static final class Taken implements IMessage {
        private int stream, frame;

        public Taken() {}

        public Taken(int stream, int frame) {
            this.stream = stream;
            this.frame = frame;
        }

        public int stream() { return stream; }
        public int frame() { return frame; }

        @Override
        public void fromBytes(ByteBuf buf) {
            stream = Bytes.readVarInt(buf);
            frame = Bytes.readVarInt(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            Bytes.writeVarInt(buf, stream);
            Bytes.writeVarInt(buf, frame);
        }

        public static final class Handler implements IMessageHandler<Taken, IMessage> {
            @Override
            public IMessage onMessage(Taken m, MessageContext c) {
                if (client() != null) client().taken(m);
                return null;
            }
        }
    }

    /**
     * Server to watcher: {@code owner}'s picture number {@code frame} of {@code stream}, taken at {@code taken} (the
     * owner's clock), for {@code screens}: the ones this watcher has in sight.
     */
    public static final class Frame implements IMessage {
        private UUID owner;
        private int stream, frame;
        private long taken;
        private List<UUID> screens;
        private byte[] picture;

        public Frame() {}

        public Frame(UUID owner, int stream, int frame, long taken, List<UUID> screens, byte[] picture) {
            this.owner = owner;
            this.stream = stream;
            this.frame = frame;
            this.taken = taken;
            this.screens = screens;
            this.picture = picture;
        }

        public UUID owner() { return owner; }
        public int stream() { return stream; }
        public int frame() { return frame; }
        public long taken() { return taken; }
        public List<UUID> screens() { return screens; }
        public byte[] picture() { return picture; }

        @Override
        public void fromBytes(ByteBuf buf) {
            owner = Bytes.readUuid(buf);
            stream = Bytes.readVarInt(buf);
            frame = Bytes.readVarInt(buf);
            taken = buf.readLong();
            screens = Bytes.readUuids(buf, MAX_SCREENS);
            picture = Bytes.readBytes(buf, MAX_FRAME_BYTES);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            Bytes.writeUuid(buf, owner);
            Bytes.writeVarInt(buf, stream);
            Bytes.writeVarInt(buf, frame);
            buf.writeLong(taken);
            Bytes.writeUuids(buf, screens);
            Bytes.writeBytes(buf, picture);
        }

        public static final class Handler implements IMessageHandler<Frame, IMessage> {
            @Override
            public IMessage onMessage(Frame m, MessageContext c) {
                if (client() != null) client().frame(m);
                return null;
            }
        }
    }

    /** Watcher to server: {@code owner}'s picture {@code frame} of {@code stream} arrived; the next one may come. */
    public static final class Received implements IMessage {
        private UUID owner;
        private int stream, frame;

        public Received() {}

        public Received(UUID owner, int stream, int frame) {
            this.owner = owner;
            this.stream = stream;
            this.frame = frame;
        }

        public UUID owner() { return owner; }
        public int stream() { return stream; }
        public int frame() { return frame; }

        @Override
        public void fromBytes(ByteBuf buf) {
            owner = Bytes.readUuid(buf);
            stream = Bytes.readVarInt(buf);
            frame = Bytes.readVarInt(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            Bytes.writeUuid(buf, owner);
            Bytes.writeVarInt(buf, stream);
            Bytes.writeVarInt(buf, frame);
        }

        public static final class Handler implements IMessageHandler<Received, IMessage> {
            @Override
            public IMessage onMessage(Received m, MessageContext c) {
                return onServer(ShareRelay::received).onMessage(m, c);
            }
        }
    }
}
