package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.ShareStream;
import com.desktopscreens.core.H264Decoder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Screens other players share with this player: their pictures arrive ({@link ShareStream.Frame}) as H.264 when
 * everyone watching can decode it (Windows' own decoder, {@link H264Support}), else as JPEG (stb, which Minecraft
 * ships, so players on any system can watch), are decoded on a background thread and drawn like our own desktop
 * ({@link ScreenRenderer}). While such a screen is drawn, {@link ShareWatching} tells the server we want its
 * pictures. Each screen shows its own thing, so an owner may send several streams; each picture says which of our
 * screens it's for, and a screen shows the stream its newest pictures came in. Client thread, except the decoding.
 */
final class SharedPictures {
    private static final long LINGER_NANOS = 3_000_000_000L;
    /**
     * An ended stream's last picture stays up this long: when a screen's source changes, the old stream ends before
     * the new one's first picture is here. Then it goes, and a screen nothing else took over goes dark.
     */
    private static final long ENDED_NANOS = 1_000_000_000L;
    /** What the H.264 decoder is told each picture lasts (100 ns units); it only needs them to go up. */
    private static final long FRAME_TIME = 333_333L;
    /** Decoded pictures waiting to be shown (held back to match the sound) at most: about 0.4 s at 30 a second. */
    private static final int MAX_WAITING = 12;
    /** A picture never waits longer than this, whatever the sound's delay seems to be. */
    private static final long MAX_HOLD_NANOS = 500_000_000L;

    /** One of an owner's streams. */
    private record StreamKey(UUID owner, int stream) {}

    /** Which stream a screen shows: the one its newest pictures came in, and the one before until that has a picture. */
    private static final class Shown {
        StreamKey current, previous;
    }

    private static final Map<StreamKey, Shared> STREAMS = new HashMap<>();
    private static final Map<ShareWatching.Screen, Shown> SHOWN = new HashMap<>();
    private static final LinkedBlockingQueue<Shared> TO_DECODE = new LinkedBlockingQueue<>();
    private static Thread decoder;
    private static int nextId;

    private SharedPictures() {}

    /** A decoded picture, when the owner's PC took it (their clock, for {@link ShareTiming}), and when it was ready here. */
    private record Decoded(ByteBuffer pixels, int width, int height, long taken, long ready) {
        Decoded(ByteBuffer pixels, int width, int height, long taken) {
            this(pixels, width, height, taken, System.nanoTime());
        }
    }

    /** One stream of an owner's pictures, on this player's screen(s). */
    private static final class Shared {
        final StreamKey key;
        final ResourceLocation location = DesktopScreens.id("shared/" + nextId++);
        final DesktopTexture texture = new DesktopTexture();
        final RenderType renderType = PictureRenderType.of(location);
        /** Pictures not decoded yet, in order: H.264 needs every one; of JPEGs only the newest counts. */
        final ConcurrentLinkedQueue<ShareStream.Frame> pictures = new ConcurrentLinkedQueue<>();
        /** On the decoder's list already. */
        final AtomicBoolean queued = new AtomicBoolean();
        /** Decoded pictures, oldest first, until they're due. */
        final ConcurrentLinkedDeque<Decoded> decoded = new ConcurrentLinkedDeque<>();
        volatile boolean closed;
        // The decoder thread's own:
        H264Decoder h264;
        boolean h264Broken;
        long h264Time;
        long lastWanted;
        /** When the owner ended it ({@link ShareStream.End}), or 0. Client thread. */
        long endedAt;
        int width, height;

        Shared(Minecraft mc, StreamKey key) {
            this.key = key;
            mc.getTextureManager().register(location, texture);
        }
    }

    /**
     * For the renderer, while it draws a screen shared with this player: the newest picture of the stream it shows,
     * or null so far.
     */
    static WorldScreens.Picture picture(Minecraft mc, ScreenGroups.Group group, UUID owner) {
        ShareWatching.Screen screen = new ShareWatching.Screen(owner, ShareStream.idOf(group.screenId(mc.level)));
        ShareWatching.wantPictures(screen, group.anchor());
        Shown shown = SHOWN.get(screen);
        if (shown == null) return null;
        long now = System.nanoTime();
        Shared s = show(STREAMS.get(shown.current), now);
        if (s == null || s.width == 0) { // just switched streams: the one before until the new one has a picture
            Shared before = shown.previous != null ? show(STREAMS.get(shown.previous), now) : null;
            if (before != null && before.width > 0) s = before;
        }
        if (s == null || s.width == 0) return null;
        return new WorldScreens.Picture(s.renderType, s.texture.getId(), s.width, s.height, 0, 0, s.width, s.height, null);
    }

    /** Marks {@code s} as wanted and puts up its newest picture that's due. */
    private static Shared show(Shared s, long now) {
        if (s == null) return null;
        s.lastWanted = now;
        UUID owner = s.key.owner();
        Decoded d = due(s, ShareTiming.soundDelay(owner));
        if (d != null) {
            s.texture.upload(MemoryUtil.memAddress(d.pixels()), d.width(), d.height());
            s.width = d.width();
            s.height = d.height();
            MemoryUtil.memFree(d.pixels());
            ShareTiming.pictureShown(owner, d.taken());
        }
        return s;
    }

    /**
     * The newest decoded picture that's due now (older ones are freed), or null. While the owner's sound is heard,
     * {@code soundDelay} says how late (watcher's clock minus owner's), and a picture is due once the sound from when
     * it was taken is heard: the sound comes about 100 ms later than the picture otherwise (measured 2026-09-25), and
     * lips out of step are easy to notice. Without sound ({@link ShareTiming#NO_SOUND}) the newest is due right away;
     * the two PCs' clocks aren't compared then, since they differ.
     */
    private static Decoded due(Shared s, long soundDelay) {
        long now = System.nanoTime();
        Decoded pick = null;
        for (Decoded d; (d = s.decoded.pollFirst()) != null; ) {
            boolean isDue = soundDelay == ShareTiming.NO_SOUND || d.taken() + soundDelay <= now || now - d.ready() > MAX_HOLD_NANOS;
            if (!isDue) {
                s.decoded.offerFirst(d);
                break;
            }
            free(pick);
            pick = d;
        }
        return pick;
    }

    /** Every client tick: forgets the pictures of streams no screen in sight showed for a while. */
    static void tick(Minecraft mc) {
        if (mc.level == null || mc.getConnection() == null) {
            for (Shared s : STREAMS.values()) close(mc, s);
            STREAMS.clear();
            SHOWN.clear();
            return;
        }
        long now = System.nanoTime();
        for (Iterator<Shared> it = STREAMS.values().iterator(); it.hasNext(); ) {
            Shared s = it.next();
            if (now - s.lastWanted > LINGER_NANOS || s.endedAt != 0 && now - s.endedAt > ENDED_NANOS) {
                close(mc, s);
                it.remove();
            }
        }
        for (Iterator<Shown> it = SHOWN.values().iterator(); it.hasNext(); ) {
            Shown shown = it.next();
            if (shown.previous != null && !STREAMS.containsKey(shown.previous)) shown.previous = null;
            if (!STREAMS.containsKey(shown.current)) it.remove();
        }
    }

    /**
     * A picture from the server, for the screens it names. Says it arrived right away, so the next can come while
     * this one is decoded.
     */
    static void frame(ShareStream.Frame frame) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) return;
        connection.send(new ServerboundCustomPayloadPacket(new ShareStream.Received(frame.owner(), frame.stream(), frame.frame())));
        List<ShareWatching.Screen> screens = new ArrayList<>();
        for (UUID id : frame.screens()) {
            ShareWatching.Screen screen = new ShareWatching.Screen(frame.owner(), id);
            if (ShareWatching.wantsPictures(screen)) screens.add(screen);
        }
        if (screens.isEmpty()) return; // not watching any more
        StreamKey key = new StreamKey(frame.owner(), frame.stream());
        Shared s = STREAMS.get(key);
        if (s == null) {
            s = new Shared(mc, key);
            s.lastWanted = System.nanoTime();
            STREAMS.put(key, s);
        }
        for (ShareWatching.Screen screen : screens) {
            Shown shown = SHOWN.computeIfAbsent(screen, k -> new Shown());
            if (key.equals(shown.current)) continue;
            shown.previous = shown.current;
            shown.current = key;
            PlayerInfo info = connection.getPlayerInfo(frame.owner());
            DesktopScreens.LOG.info("Sharing: a screen of {}'s shows their stream {}{}", info != null ? info.getProfile().getName() : "?",
                    frame.stream(), shown.previous != null ? " (was " + shown.previous.stream() + ")" : "");
        }
        s.pictures.add(frame);
        wake(s);
    }

    /**
     * We stopped wanting pictures of {@code screen} (hidden, or out of sight for a while), so the server sends none:
     * it shows nothing until its pictures come again. Otherwise its stream's last picture could come back up, frozen,
     * if that stream ended meanwhile and its end never reached us (the server forgets the watchers of an owner nobody
     * watches any more, so it can't pass the end on).
     */
    static void forget(ShareWatching.Screen screen) {
        SHOWN.remove(screen);
    }

    /** The owner ended a stream: its picture goes shortly ({@link #ENDED_NANOS}). */
    static void end(ShareStream.End end) {
        Shared s = STREAMS.get(new StreamKey(end.owner(), end.stream()));
        if (s != null && s.endedAt == 0) s.endedAt = System.nanoTime();
    }

    /** Puts {@code s} on the decoder's list, unless it's there already. */
    private static void wake(Shared s) {
        if (s.queued.compareAndSet(false, true)) TO_DECODE.add(s);
        startDecoder();
    }

    private static void close(Minecraft mc, Shared s) {
        s.closed = true;
        mc.getTextureManager().release(s.location);
        freeAll(s);
        wake(s); // the decoder thread closes its H.264 decoder, on its own thread
    }

    private static void free(Decoded d) {
        if (d != null) MemoryUtil.memFree(d.pixels());
    }

    private static void freeAll(Shared s) {
        for (Decoded d; (d = s.decoded.pollFirst()) != null; ) free(d);
    }

    private static void startDecoder() {
        if (decoder != null) return;
        decoder = new Thread(SharedPictures::decodeLoop, "Desktop Screens shared pictures");
        decoder.setDaemon(true);
        decoder.start();
    }

    /**
     * Decodes what came in for each owner: every H.264 picture, in order (each builds on the ones before), but of the
     * JPEGs waiting only the newest. They wait in {@link Shared#decoded} until they're due ({@link #due}).
     */
    private static void decodeLoop() {
        while (true) {
            Shared s;
            try {
                s = TO_DECODE.take();
            } catch (InterruptedException e) {
                return;
            }
            s.queued.set(false); // before taking the pictures, so one that comes in meanwhile wakes us again
            if (s.closed) {
                s.pictures.clear();
                closeH264(s);
                continue;
            }
            List<ShareStream.Frame> batch = new ArrayList<>();
            for (ShareStream.Frame f; (f = s.pictures.poll()) != null; ) batch.add(f);
            int lastJpeg = -1;
            for (int i = 0; i < batch.size(); i++) {
                if (!ShareStream.isH264(batch.get(i).picture())) lastJpeg = i;
            }
            for (int i = 0; i < batch.size(); i++) {
                byte[] picture = batch.get(i).picture();
                long taken = batch.get(i).taken();
                boolean h264 = ShareStream.isH264(picture);
                if (!h264 && i != lastJpeg) continue; // an older JPEG: the newer one replaces it anyway
                try {
                    Decoded d = h264 ? decodeH264(s, picture, taken) : decode(picture, taken);
                    if (d != null) {
                        s.decoded.offerLast(d);
                        while (s.decoded.size() > MAX_WAITING) free(s.decoded.pollFirst());
                    }
                } catch (RuntimeException | LinkageError e) {
                    if (h264) {
                        H264Support.failed(e);
                        s.h264Broken = true;
                        closeH264(s);
                    } else {
                        DesktopScreens.LOG.error("Sharing: couldn't decode a shared picture", e);
                    }
                }
            }
            if (s.closed) freeAll(s); // closed while we decoded: nobody will take them
        }
    }

    /**
     * An H.264 picture ({@link ShareStream#packH264}): Windows' decoder, then NV12 to BGRA, cut to the picture's own
     * size. Null if nothing came out (yet).
     */
    private static Decoded decodeH264(Shared s, byte[] picture, long taken) {
        if (s.h264Broken) return null; // JPEG comes once the server hears we can't
        if (s.h264 == null) s.h264 = H264Decoder.open();
        int[] size = ShareStream.h264Size(picture);
        int w = size[0], h = size[1];
        ByteBuffer[] out = {null};
        s.h264.decode(ShareStream.h264Frame(picture), s.h264Time, FRAME_TIME, (nv12, stride, rows) -> {
            if (w > stride || h > rows) return;
            if (out[0] == null) out[0] = MemoryUtil.memAlloc(w * h * 4);
            Nv12.toBgra(nv12, stride, rows, w, h, MemoryUtil.memAddress(out[0]));
        });
        s.h264Time += FRAME_TIME;
        return out[0] == null ? null : new Decoded(out[0], w, h, taken);
    }

    private static void closeH264(Shared s) {
        if (s.h264 != null) {
            s.h264.close();
            s.h264 = null;
        }
    }

    /** Decodes the picture's strips ({@link ShareStream#pack}) and stacks them into one BGRA picture. Null if it's broken. */
    private static Decoded decode(byte[] picture, long taken) {
        int[][] strips = ShareStream.unpack(picture);
        if (strips == null) return null;
        List<ByteBuffer> decoded = new ArrayList<>(strips.length);
        int width = -1, height = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), channels = stack.mallocInt(1);
            for (int[] strip : strips) {
                ByteBuffer in = MemoryUtil.memAlloc(strip[1]);
                ByteBuffer pixels;
                try {
                    in.put(picture, strip[0], strip[1]).flip();
                    pixels = STBImage.stbi_load_from_memory(in, w, h, channels, 4);
                } finally {
                    MemoryUtil.memFree(in);
                }
                if (pixels == null || width >= 0 && w.get(0) != width) {
                    if (pixels != null) freeStb(pixels);
                    return null;
                }
                width = w.get(0);
                height += h.get(0);
                decoded.add(pixels);
            }
            ByteBuffer whole = MemoryUtil.memAlloc(width * height * 4);
            // A duplicate: put() moves the source's position to its end, and the strip must still be freed by its start.
            for (ByteBuffer part : decoded) whole.put(part.duplicate());
            return new Decoded(whole.flip(), width, height, taken);
        } finally {
            for (ByteBuffer part : decoded) freeStb(part);
        }
    }

    /**
     * Frees what stb allocated, by the buffer's start. {@code STBImage.stbi_image_free(ByteBuffer)} frees at the
     * buffer's current position instead; after reading from it, that's not the start, and freeing it corrupted
     * jemalloc's heap (the watcher crashed in jemalloc.dll a few pictures later, 2026-09-24).
     */
    private static void freeStb(ByteBuffer pixels) {
        STBImage.nstbi_image_free(MemoryUtil.memAddress0(pixels));
    }
}
