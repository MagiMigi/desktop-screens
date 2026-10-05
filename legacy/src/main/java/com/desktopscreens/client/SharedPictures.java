package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.Net;
import com.desktopscreens.ShareStream;
import com.desktopscreens.core.H264Decoder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Screens other players share with this player: their pictures arrive ({@link ShareStream.Frame}) as H.264 when
 * everyone watching can decode it (Windows' own decoder, {@link H264Support}), else as JPEG (Java's own reader on
 * 1.12.2, so players on any system can watch), are decoded on a background thread and drawn like our own desktop
 * ({@link ScreenRenderer}). While such a screen is drawn, {@link ShareWatching} tells the server we want its pictures.
 * Each screen shows its own thing, so an owner may send several streams; each picture says which of our screens it's
 * for, and a screen shows the stream its newest pictures came in. Client thread, except the decoding.
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
    /**
     * Pictures are paced by how long the slowest of the last {@link #PACE_SAMPLES} took to arrive (the {@link
     * #PACE_RANK}th slowest, so one odd one doesn't count), plus {@link #PACE_MARGIN_NANOS}.
     */
    private static final int PACE_SAMPLES = 60, PACE_RANK = 3;
    private static final long PACE_MARGIN_NANOS = 5_000_000L;

    /** One of an owner's streams. */
    private static final class StreamKey {
        final UUID owner;
        final int stream;

        StreamKey(UUID owner, int stream) {
            this.owner = owner;
            this.stream = stream;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof StreamKey && ((StreamKey) o).owner.equals(owner) && ((StreamKey) o).stream == stream;
        }

        @Override
        public int hashCode() {
            return Objects.hash(owner, stream);
        }
    }

    /** Which stream a screen shows: the one its newest pictures came in, and the one before until that has a picture. */
    private static final class Shown {
        StreamKey current, previous;
    }

    private static final Map<StreamKey, Shared> STREAMS = new HashMap<>();
    private static final Map<ShareWatching.Screen, Shown> SHOWN = new HashMap<>();
    private static final LinkedBlockingQueue<Shared> TO_DECODE = new LinkedBlockingQueue<>();
    private static Thread decoder;

    private SharedPictures() {}

    /** A decoded picture, when the owner's PC took it (their clock, for {@link ShareTiming}), and when it was ready here. */
    private static final class Decoded {
        final int[] pixels;
        final int width, height;
        final long taken, ready = System.nanoTime();

        Decoded(int[] pixels, int width, int height, long taken) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.taken = taken;
        }
    }

    /** One stream of an owner's pictures, on this player's screen(s). */
    private static final class Shared {
        final StreamKey key;
        final DesktopTexture texture = new DesktopTexture();
        /** The pictures go up to the texture through this. Client thread. */
        ByteBuffer upload;
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
        byte[] nv12 = new byte[0];
        /** How long the last pictures took from the owner's screen to decoded here, oldest overwritten first. */
        final long[] latencies = new long[PACE_SAMPLES];
        int latencyCount, latencyNext;
        /**
         * How long after it was taken a picture shows, at least (both in the same mix of the two PCs' clocks as
         * {@link ShareTiming#soundDelay}), or {@link Long#MIN_VALUE} before the first. Set by the decoder thread.
         */
        volatile long paceDelay = Long.MIN_VALUE;
        long lastWanted;
        /** When the owner ended it ({@link ShareStream.End}), or 0. Client thread. */
        long endedAt;
        int width, height;

        Shared(StreamKey key) {
            this.key = key;
        }
    }

    /**
     * For the renderer, while it draws a screen shared with this player: the newest picture of the stream it shows,
     * or null so far.
     */
    static WorldScreens.Picture picture(Minecraft mc, ScreenGroups.Group group, UUID owner) {
        ShareWatching.Screen screen = new ShareWatching.Screen(owner, ShareStream.idOf(group.screenId(mc.world)));
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
        return new WorldScreens.Picture(s.texture, s.width, s.height, 0, 0, s.width, s.height, null);
    }

    /** Marks {@code s} as wanted and puts up its newest picture that's due. */
    private static Shared show(Shared s, long now) {
        if (s == null) return null;
        s.lastWanted = now;
        UUID owner = s.key.owner;
        Decoded d = due(s, ShareTiming.soundDelay(owner));
        if (d != null) {
            int count = d.width * d.height;
            s.upload = Mem.direct(s.upload, count * 4);
            s.upload.asIntBuffer().put(d.pixels, 0, count); // BGRA in the PC's byte order: B, G, R, A
            s.texture.upload(s.upload, d.width, d.height);
            s.width = d.width;
            s.height = d.height;
            ShareTiming.pictureShown(owner, d.taken);
        }
        return s;
    }

    /**
     * The newest decoded picture that's due now (older ones are dropped), or null. A picture is due once its time on
     * the owner's screen plus a fixed delay has come, so pictures show as evenly as they were taken, however unevenly
     * they arrive (a 1.12.2 server passes them on once per tick). The delay is how long nearly all recent pictures
     * took to get here ({@link #pace}); while the owner's sound is heard and comes later, it's the sound's.
     */
    private static Decoded due(Shared s, long soundDelay) {
        long now = System.nanoTime();
        long delay = soundDelay == ShareTiming.NO_SOUND ? s.paceDelay : Math.max(soundDelay, s.paceDelay);
        Decoded pick = null;
        for (Decoded d; (d = s.decoded.pollFirst()) != null; ) {
            boolean isDue = delay == Long.MIN_VALUE || d.taken + delay <= now || now - d.ready > MAX_HOLD_NANOS;
            if (!isDue) {
                s.decoded.offerFirst(d);
                break;
            }
            pick = d;
        }
        return pick;
    }

    /**
     * A picture took {@code latency} from the owner's screen to decoded here (with the clocks' difference in it): the
     * pace delay becomes the {@link #PACE_RANK}th longest of the recent ones plus a margin. Decoder thread.
     */
    private static void pace(Shared s, long latency) {
        s.latencies[s.latencyNext] = latency;
        s.latencyNext = (s.latencyNext + 1) % PACE_SAMPLES;
        s.latencyCount = Math.min(PACE_SAMPLES, s.latencyCount + 1);
        long[] recent = Arrays.copyOf(s.latencies, s.latencyCount);
        Arrays.sort(recent);
        s.paceDelay = recent[Math.max(0, recent.length - PACE_RANK)] + PACE_MARGIN_NANOS;
    }

    /** Every client tick: forgets the pictures of streams no screen in sight showed for a while. */
    static void tick(Minecraft mc) {
        if (mc.world == null || mc.getConnection() == null) {
            for (Shared s : STREAMS.values()) close(s);
            STREAMS.clear();
            SHOWN.clear();
            return;
        }
        long now = System.nanoTime();
        for (Iterator<Shared> it = STREAMS.values().iterator(); it.hasNext(); ) {
            Shared s = it.next();
            if (now - s.lastWanted > LINGER_NANOS || s.endedAt != 0 && now - s.endedAt > ENDED_NANOS) {
                close(s);
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
     * this one is decoded. Client thread.
     */
    static void frame(ShareStream.Frame frame) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getConnection() == null) return;
        Net.CHANNEL.sendToServer(new ShareStream.Received(frame.owner(), frame.stream(), frame.frame()));
        List<ShareWatching.Screen> screens = new ArrayList<>();
        for (UUID id : frame.screens()) {
            ShareWatching.Screen screen = new ShareWatching.Screen(frame.owner(), id);
            if (ShareWatching.wantsPictures(screen)) screens.add(screen);
        }
        if (screens.isEmpty()) return; // not watching any more
        StreamKey key = new StreamKey(frame.owner(), frame.stream());
        Shared s = STREAMS.get(key);
        if (s == null) {
            s = new Shared(key);
            s.lastWanted = System.nanoTime();
            STREAMS.put(key, s);
        }
        for (ShareWatching.Screen screen : screens) {
            Shown shown = SHOWN.computeIfAbsent(screen, k -> new Shown());
            if (key.equals(shown.current)) continue;
            shown.previous = shown.current;
            shown.current = key;
            NetworkPlayerInfo info = mc.getConnection().getPlayerInfo(frame.owner());
            DesktopScreens.LOG.info("Sharing: a screen of {}'s shows their stream {}{}", info != null ? info.getGameProfile().getName() : "?",
                    frame.stream(), shown.previous != null ? " (was " + shown.previous.stream + ")" : "");
        }
        s.pictures.add(frame);
        wake(s);
    }

    /**
     * We stopped wanting pictures of {@code screen} (hidden, or out of sight for a while), so the server sends none:
     * it shows nothing until its pictures come again. Otherwise its stream's last picture could come back up, frozen.
     */
    static void forget(ShareWatching.Screen screen) {
        SHOWN.remove(screen);
    }

    /** The owner ended a stream: its picture goes shortly ({@link #ENDED_NANOS}). Client thread. */
    static void end(ShareStream.End end) {
        Shared s = STREAMS.get(new StreamKey(end.owner(), end.stream()));
        if (s != null && s.endedAt == 0) s.endedAt = System.nanoTime();
    }

    /** Puts {@code s} on the decoder's list, unless it's there already. */
    private static void wake(Shared s) {
        if (s.queued.compareAndSet(false, true)) TO_DECODE.add(s);
        startDecoder();
    }

    private static void close(Shared s) {
        s.closed = true;
        s.texture.delete();
        s.decoded.clear();
        wake(s); // the decoder thread closes its H.264 decoder, on its own thread
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
                        while (s.decoded.size() > MAX_WAITING) s.decoded.pollFirst();
                        pace(s, d.ready - d.taken);
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
            if (s.closed) s.decoded.clear(); // closed while we decoded: nobody will take them
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
        int[][] out = {null};
        s.h264.decode(ShareStream.h264Frame(picture), s.h264Time, FRAME_TIME, (address, stride, rows) -> {
            if (w > stride || h > rows) return;
            int bytes = stride * rows * 3 / 2;
            if (s.nv12.length < bytes) s.nv12 = new byte[bytes];
            Mem.read(address, s.nv12, 0, bytes);
            if (out[0] == null) out[0] = new int[w * h];
            Nv12.toBgra(s.nv12, stride, rows, w, h, out[0]);
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
        List<BufferedImage> decoded = new ArrayList<>(strips.length);
        int width = -1, height = 0;
        try {
            for (int[] strip : strips) {
                // Read in memory (from a plain stream, ImageIO would go through a temporary file).
                BufferedImage image = ImageIO.read(new MemoryCacheImageInputStream(new ByteArrayInputStream(picture, strip[0], strip[1])));
                if (image == null || width >= 0 && image.getWidth() != width) return null;
                width = image.getWidth();
                height += image.getHeight();
                decoded.add(image);
            }
        } catch (IOException e) {
            return null;
        }
        int[] whole = new int[width * height];
        int y = 0;
        for (BufferedImage image : decoded) {
            toBgra(image, whole, y * width);
            y += image.getHeight();
        }
        return new Decoded(whole, width, height, taken);
    }

    /** An image's pixels as opaque ints 0xAARRGGBB, which is BGRA in the PC's byte order, into {@code out} from {@code offset}. */
    private static void toBgra(BufferedImage image, int[] out, int offset) {
        int w = image.getWidth(), h = image.getHeight();
        // What Java's reader makes of a color JPEG: B, G, R bytes. Straight from those (getRGB would convert each
        // pixel through the color model, too slow for 30 pictures a second).
        if (image.getType() == BufferedImage.TYPE_3BYTE_BGR && image.getRaster().getDataBuffer() instanceof DataBufferByte) {
            byte[] bgr = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
            if (bgr.length == w * h * 3) {
                for (int i = 0, j = 0; i < w * h; i++, j += 3) {
                    out[offset + i] = 0xFF000000 | (bgr[j + 2] & 255) << 16 | (bgr[j + 1] & 255) << 8 | bgr[j] & 255;
                }
                return;
            }
        }
        image.getRGB(0, 0, w, h, out, offset, w);
    }
}
