package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.Net;
import com.desktopscreens.ShareStream;
import com.desktopscreens.core.AppWindow;
import com.desktopscreens.core.DesktopCapture;
import com.desktopscreens.core.H264Encoder;
import com.desktopscreens.core.Monitor;
import com.desktopscreens.core.PictureFeed;
import com.desktopscreens.core.Win32;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends this player's desktop to the players watching their shared screens ({@link com.desktopscreens.ShareRelay}):
 * what each watched screen shows (its own monitor or window, or the tablet's), scaled down to the server's limit
 * (1280x720 by default) and compressed, only while someone has the screen in sight. Screens showing the same thing
 * share one stream; each stream has its own capture reader and encoder, and a window is read from its own capture
 * (Windows Graphics Capture), so friends see it right while other windows cover it. A stream's next picture goes once
 * the server has an earlier one, at most as many a second as the server allows, and never one that didn't change.
 *
 * <p>H.264 through Windows' own encoder while everyone watching can decode it; else JPEG, in {@link #STRIPS} horizontal
 * strips that as many threads compress at once. On 1.12.2 the JPEGs come from Java's own writer (the newer versions
 * use stb, which comes with LWJGL 3), and the pictures are worked on as int arrays ({@link Mem}).
 */
final class ShareStreamer {
    private static final float QUALITY = 0.8f;
    private static final int STRIPS = 4;

    // Client thread, all of it; the encoders' threads only get what update() hands them.
    private static List<String> watchers = Collections.emptyList();
    private static ShareStream.Limits limits = new ShareStream.Limits(30, 1280, 720, 4);
    /** The screens someone has in sight (the others are only near one, for the sound). */
    private static List<ShareStream.Wanted> wanted = Collections.emptyList();
    /** One per thing shown on watched screens, by {@link SourceChoice#key}. */
    private static final Map<String, Sender> SENDERS = new LinkedHashMap<>();
    /** The streams' encoders by stream number, for the server's receipts, which arrive on the network thread. */
    private static final Map<Integer, Encoder> ENCODERS = new ConcurrentHashMap<>();
    private static int nextStream;
    private static boolean saidTooMany;

    private ShareStreamer() {}

    /** The server says who's watching or listening now, and which screens someone has in sight; nobody means stop. Client thread. */
    static void status(ShareStream.Status status) {
        watchers = Collections.unmodifiableList(new ArrayList<>(status.watchers()));
        limits = status.limits();
        wanted = status.pictures() ? Collections.unmodifiableList(new ArrayList<>(status.screens())) : Collections.emptyList();
        int h264 = 0;
        for (ShareStream.Wanted w : wanted) {
            if (w.h264()) h264++;
        }
        String formats = h264 == wanted.size() ? "H.264" : h264 == 0 ? "JPEG" : "H.264 for " + h264 + ", JPEG for " + (wanted.size() - h264);
        DesktopScreens.LOG.info("Sharing: {}", watchers.isEmpty() ? "nobody's watching any more"
                : String.join(", ", watchers) + " watching or listening; "
                        + (wanted.isEmpty() ? "no pictures (nobody has a screen in sight)"
                                : wanted.size() + (wanted.size() == 1 ? " screen" : " screens") + " in sight (" + formats + "), up to "
                                + limits.maxFps() + " pictures a second at " + limits.maxWidth() + "x" + limits.maxHeight())
                        + (status.sound() ? ", sound" : ", no sound"));
        if (wanted.isEmpty()) stopAll();
    }

    /** The server has a stream's picture; its next may go. Any thread (the network thread). */
    static void taken(ShareStream.Taken taken) {
        Encoder encoder = ENCODERS.get(taken.stream());
        if (encoder != null) encoder.taken(taken.frame());
    }

    /** Someone starts watching a screen, or missed pictures of it: its stream sends a keyframe. Client thread. */
    static void keyframe(ShareStream.Keyframe keyframe) {
        for (Sender s : SENDERS.values()) {
            if (s.screens.contains(keyframe.screen())) s.keyframe();
        }
    }

    /** Screens showing the same thing: one stream. */
    private static final class Group {
        final SourceChoice choice;
        final List<UUID> screens = new ArrayList<>();
        boolean h264 = true; // everyone watching any of them can decode it

        Group(SourceChoice choice) {
            this.choice = choice;
        }
    }

    /** Every client tick: starts, keeps up and stops the streams. */
    static void tick(Minecraft mc) {
        if (mc.world == null) {
            watchers = Collections.emptyList();
            wanted = Collections.emptyList();
        }
        if (wanted.isEmpty() || !Win32.SUPPORTED || mc.getConnection() == null) {
            stopAll();
            return;
        }
        try {
            Map<String, Group> byKey = new LinkedHashMap<>();
            for (ShareStream.Wanted w : wanted) {
                SourceChoice choice = SourceChoice.of(w.screen());
                Group g = byKey.computeIfAbsent(choice.key(), k -> new Group(choice));
                g.screens.add(w.screen());
                g.h264 &= w.h264();
            }
            // Over the server's limit, the streams already running keep going, oldest first, and new ones wait for a
            // free one: nobody's picture is taken away for another.
            Map<String, Group> groups = new LinkedHashMap<>();
            for (String key : SENDERS.keySet()) {
                Group g = byKey.get(key);
                if (g != null) groups.put(key, g);
            }
            groups.putAll(byKey);
            int max = Math.max(1, limits.maxStreams());
            if (groups.size() > max) {
                if (!saidTooMany) {
                    DesktopScreens.LOG.info("Sharing: watched screens show {} different things, but the server allows sending {} of them; the others stay dark",
                            groups.size(), max);
                }
                saidTooMany = true;
                Iterator<String> extra = groups.keySet().iterator();
                for (int i = 0; extra.hasNext(); i++) {
                    extra.next();
                    if (i >= max) extra.remove();
                }
            } else {
                saidTooMany = false;
            }
            for (Iterator<Map.Entry<String, Sender>> it = SENDERS.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Sender> e = it.next();
                if (!groups.containsKey(e.getKey())) {
                    e.getValue().stop();
                    it.remove();
                }
            }
            for (Map.Entry<String, Group> e : groups.entrySet()) {
                Group g = e.getValue();
                SENDERS.computeIfAbsent(e.getKey(), k -> new Sender(mc, ++nextStream)).update(g.choice, g.screens, g.h264);
            }
        } catch (RuntimeException | LinkageError e) {
            DesktopScreens.LOG.error("Sharing: couldn't send the desktop", e);
            wanted = Collections.emptyList();
            stopAll();
        }
    }

    private static void stopAll() {
        for (Sender s : SENDERS.values()) s.stop();
        SENDERS.clear();
    }

    /**
     * One stream: what some watched screens show, read from its window's own capture ({@link LiveWindow}) or its
     * monitor's ({@link LiveCapture}, cut to the window if it has one and Graphics Capture can't take it), and its
     * encoder. Client thread.
     */
    private static final class Sender {
        final Minecraft mc;
        final int stream;
        List<UUID> screens = Collections.emptyList();
        private LiveCapture live;
        private LiveWindow liveWindow;
        /** A window Graphics Capture couldn't take (or stopped taking): its part of the monitor instead. */
        private AppWindow cutOut;
        private String described = "";
        private Encoder encoder;

        Sender(Minecraft mc, int stream) {
            this.mc = mc;
            this.stream = stream;
        }

        void update(SourceChoice choice, List<UUID> screens, boolean h264) {
            WorldScreens.Shown shown = WorldScreens.shown(choice);
            AppWindow window = shown.window;
            PictureFeed feed = null;
            int[] crop = null;
            String what = null;
            if (window != null && !window.sameAs(cutOut)) {
                if (liveWindow == null || !liveWindow.shows(window)) {
                    release();
                    liveWindow = LiveWindow.acquire(mc, window);
                }
                if (liveWindow.capture().error() == null) {
                    liveWindow.refresh();
                    feed = liveWindow.capture();
                    what = window + " by itself";
                } else {
                    DesktopScreens.LOG.info("Sharing: {} can't be captured by itself; sending its part of {} instead", window, shown.monitor);
                    cutOut = window;
                }
            }
            if (feed == null) {
                if (liveWindow != null) {
                    liveWindow.release();
                    liveWindow = null;
                }
                if (live == null || !live.monitor().sameAs(shown.monitor)) {
                    release();
                    live = LiveCapture.acquire(mc, shown.monitor);
                }
                live.refresh();
                feed = live.capture();
                crop = crop(shown);
                what = window != null ? window + " cut from " + shown.monitor.label() : shown.monitor.label();
            }
            if (!what.equals(described)) {
                described = what;
                DesktopScreens.LOG.info("Sharing: stream {} sends {}", stream, what);
            }
            boolean gained = !this.screens.containsAll(screens);
            this.screens = Collections.unmodifiableList(new ArrayList<>(screens));
            boolean fresh = encoder == null;
            if (fresh) {
                encoder = new Encoder(mc, stream);
                ENCODERS.put(stream, encoder);
            }
            encoder.update(feed, crop, this.screens, what, limits, h264);
            // A screen that joins a running stream: its watchers need a keyframe (a new encoder starts with one).
            if (gained && !fresh) encoder.keyframe();
        }

        void keyframe() {
            if (encoder != null) encoder.keyframe();
        }

        private void release() {
            if (live != null) {
                live.release();
                live = null;
            }
            if (liveWindow != null) {
                liveWindow.release();
                liveWindow = null;
            }
        }

        void stop() {
            if (encoder != null) {
                ENCODERS.remove(stream, encoder);
                encoder.stop();
                encoder = null;
            }
            release();
            // So watchers take its last picture down instead of keeping it up, frozen.
            if (mc.getConnection() != null) Net.CHANNEL.sendToServer(new ShareStream.EndUp(stream));
            DesktopScreens.LOG.info("Sharing: stream {} stopped", stream);
        }
    }

    /** The part of the monitor's picture to send, {left, top, right, bottom} in its pixels, or null for all of it. */
    private static int[] crop(WorldScreens.Shown shown) {
        int[] a = shown.area;
        if (a == null) return null;
        Monitor m = shown.monitor;
        return new int[] {a[0] - m.x, a[1] - m.y, a[2] - m.x, a[3] - m.y};
    }

    /**
     * Top right, over the HUD and the desktop view: who's watching your screen right now. Returns where it drew,
     * {left, top, right, bottom} in GUI units, or null.
     */
    static int[] renderNotice(Minecraft mc, int screenWidth) {
        List<String> w = watchers;
        if (w.isEmpty()) return null;
        ITextComponent who = w.size() == 1 ? new TextComponentTranslation("desktopscreens.share.watching_one", w.get(0))
                : w.size() == 2 ? new TextComponentTranslation("desktopscreens.share.watching_two", w.get(0), w.get(1))
                : new TextComponentTranslation("desktopscreens.share.watching_many", w.size());
        String line = TextFormatting.RED + "● " + TextFormatting.WHITE + who.getFormattedText();
        FontRenderer font = mc.fontRenderer;
        int x = screenWidth - font.getStringWidth(line) - 6;
        font.drawStringWithShadow(line, x, 6, 0xFFFFFFFF);
        return new int[] {x - 2, 4, screenWidth - 4, 6 + font.FONT_HEIGHT + 2}; // with its shadow and a little room
    }

    /** Copies the part to send out of the capture's newest picture, under its lock: one copy into an array. */
    private static final class Copy implements DesktopCapture.FrameSink {
        PictureFeed source;
        int[] crop;
        int[] pixels = new int[0];
        long taken;
        int width, height;

        @Override
        public void accept(long address, int w, int h) {
            taken = source.frameTime();
            int x0 = 0, y0 = 0, x1 = w, y1 = h;
            if (crop != null) {
                x0 = MathHelper.clamp(crop[0], 0, w);
                y0 = MathHelper.clamp(crop[1], 0, h);
                x1 = MathHelper.clamp(crop[2], x0, w);
                y1 = MathHelper.clamp(crop[3], y0, h);
                if (x1 - x0 < 1 || y1 - y0 < 1) { // not on this monitor: all of it
                    x0 = y0 = 0;
                    x1 = w;
                    y1 = h;
                }
            }
            width = x1 - x0;
            height = y1 - y0;
            if (pixels.length < width * height) pixels = new int[width * height];
            if (width == w) {
                Mem.read(address + (long) y0 * w * 4, pixels, 0, width * height);
            } else {
                for (int r = 0; r < height; r++) Mem.read(address + ((long) (y0 + r) * w + x0) * 4, pixels, r * width, width);
            }
        }
    }

    /** Takes one stream's pictures, has them scaled and compressed in strips by the workers, and sends them. */
    private static final class Encoder implements Runnable {
        /** If the server never confirms a picture (it had nobody to pass it to, say), carry on after this long. */
        private static final long RECEIPT_TIMEOUT_NANOS = 2_000_000_000L, STATS_NANOS = 10_000_000_000L;

        private final Minecraft mc;
        private final int stream;
        private final ExecutorService workers = Executors.newFixedThreadPool(STRIPS, task -> {
            Thread t = new Thread(task, "Desktop Screens sharing, compressing");
            t.setDaemon(true);
            return t;
        });
        private volatile boolean running = true;
        private volatile PictureFeed feed;
        private volatile int[] crop;
        /** The screens its pictures are for, and what it sends (for the log). */
        private volatile List<UUID> screens = Collections.emptyList();
        private volatile String what = "";
        private volatile int fps = 30, maxW = 1280, maxH = 720;
        private volatile int sent, confirmed;
        private volatile long sentAt;
        /** Everyone watching can decode H.264. */
        private volatile boolean h264Allowed;
        /** The server asked for a keyframe: someone needs a picture to start from. */
        private final AtomicBoolean keyframeWanted = new AtomicBoolean();
        // The encoder thread's own:
        private final long startNanos = System.nanoTime();
        private int[] scaled = new int[0];
        private byte[] nv12 = new byte[0];
        private H264Encoder h264;
        private boolean h264Failed;

        Encoder(Minecraft mc, int stream) {
            this.mc = mc;
            this.stream = stream;
            Thread thread = new Thread(this, "Desktop Screens sharing, stream " + stream);
            thread.setDaemon(true);
            thread.start();
        }

        void update(PictureFeed f, int[] crop, List<UUID> screens, String what, ShareStream.Limits limits, boolean h264) {
            this.crop = crop;
            this.screens = screens;
            this.what = what;
            fps = Math.max(1, limits.maxFps());
            maxW = limits.maxWidth();
            maxH = limits.maxHeight();
            h264Allowed = h264;
            feed = f;
        }

        void taken(int frame) {
            if (frame > confirmed) confirmed = frame;
        }

        void keyframe() {
            keyframeWanted.set(true);
        }

        /** After this returns, it sends nothing more (its stream's end may go out). */
        void stop() {
            running = false;
        }

        @Override
        public void run() {
            Copy copy = new Copy();
            PictureFeed seenFeed = null;
            long seen = 0, lastHash = 0, next = 0;
            int number = 0;
            long statsFrom = System.nanoTime(), statsBytes = 0, statsWork = 0;
            int statsFrames = 0;
            try {
                while (running) {
                    long now = System.nanoTime();
                    PictureFeed c = feed;
                    // A few pictures at a time (ShareStream.IN_FLIGHT): the next once the server has an earlier one.
                    boolean ready = sent - confirmed < ShareStream.IN_FLIGHT || now - sentAt > RECEIPT_TIMEOUT_NANOS;
                    if (c == null || !ready || now < next) {
                        Thread.sleep(2);
                        continue;
                    }
                    if (c != seenFeed) { // a new capture counts its pictures from 0 again
                        seenFeed = c;
                        seen = 0;
                    }
                    copy.source = c;
                    copy.crop = crop;
                    long got = c.readLatest(seen, copy);
                    // Nothing new; but someone who starts watching needs a picture to start from, also of a still desktop.
                    boolean again = got == seen && copy.width > 0 && keyframeWanted.get();
                    if (got == seen && !again) {
                        Thread.sleep(4);
                        continue;
                    }
                    seen = got;
                    boolean key = keyframeWanted.getAndSet(false);
                    long start = System.nanoTime();
                    final int cw = copy.width, ch = copy.height;
                    long hash = hash(copy.pixels, cw * ch, cw, ch);
                    if (hash == lastHash && !key) { // GDI hands over every grab, changed or not
                        next = now + 1_000_000_000L / fps;
                        continue;
                    }
                    lastHash = hash;
                    // At most as many pixels as the server allows (maxW x maxH), whatever the shape: a 1024x768 monitor fits
                    // 1280x720's and goes out at its own size.
                    double scale = Math.min(1.0, Math.sqrt((double) maxW * maxH / ((double) cw * ch)));
                    int sw = Math.max(1, (int) Math.round(cw * scale)), sh = Math.max(1, (int) Math.round(ch * scale));
                    byte[] picture = h264Allowed && !h264Failed ? h264Picture(copy, sw, sh, now, key) : null;
                    if (picture == null) { // JPEG: not everyone watching can decode H.264, or it failed here
                        closeH264();
                        picture = jpegPicture(copy, sw, sh);
                    }
                    if (picture.length == 0 || picture.length > ShareStream.MAX_FRAME_BYTES) continue;
                    sendFrame(++number, copy.taken, picture);
                    sentAt = now;
                    next = now + 1_000_000_000L / fps;
                    statsFrames++;
                    statsBytes += picture.length;
                    statsWork += System.nanoTime() - start;
                    if (now - statsFrom > STATS_NANOS) {
                        double seconds = (now - statsFrom) / 1e9;
                        DesktopScreens.LOG.info("Sharing stream {}, {} ({}): {} pictures a second at {}x{}, {} KB each, scaling and compressing took {} ms each",
                                stream, what, h264 != null ? "H.264" : "JPEG", String.format("%.1f", statsFrames / seconds), sw, sh,
                                statsBytes / Math.max(1, statsFrames) / 1024, String.format("%.1f", statsWork / 1e6 / Math.max(1, statsFrames)));
                        statsFrom = now;
                        statsFrames = 0;
                        statsBytes = statsWork = 0;
                    }
                }
            } catch (InterruptedException ignored) {
                // closing
            } catch (ExecutionException | RuntimeException | LinkageError e) {
                DesktopScreens.LOG.error("Sharing: couldn't compress the desktop", e);
            } finally {
                closeH264();
                workers.shutdownNow();
                try {
                    workers.awaitTermination(1, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // done anyway
                }
            }
        }

        /** JPEG in strips: each worker scales its strip and compresses it. */
        private byte[] jpegPicture(Copy copy, int ow, int oh) throws InterruptedException, ExecutionException {
            final int cw = copy.width, ch = copy.height;
            final boolean scaling = ow != cw || oh != ch;
            if (scaling && scaled.length < ow * oh) scaled = new int[ow * oh];
            final int[] from = copy.pixels, to = scaled;
            // Strips a whole number of 16-row JPEG blocks high, so no seams show where they meet.
            int stripRows = ((oh + STRIPS - 1) / STRIPS + 15) / 16 * 16;
            List<Future<byte[]>> strips = new ArrayList<>();
            for (int y = 0; y < oh; y += stripRows) {
                final int y0 = y, y1 = Math.min(oh, y + stripRows);
                strips.add(workers.submit(() -> {
                    if (scaling) bilinear(from, cw, ch, to, ow, oh, y0, y1);
                    return jpeg(scaling ? to : from, y0 * ow, ow, y1 - y0);
                }));
            }
            List<byte[]> parts = new ArrayList<>();
            for (Future<byte[]> f : strips) parts.add(f.get());
            return ShareStream.pack(parts);
        }

        /** One strip as a JPEG: Java's own writer. */
        private static byte[] jpeg(int[] pixels, int offset, int w, int h) throws IOException {
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            // BGRA ints are 0xAARRGGBB: RGB, with alpha ignored. Straight into the image's pixels (setRGB would
            // convert each one through the color model).
            System.arraycopy(pixels, offset, ((DataBufferInt) image.getRaster().getDataBuffer()).getData(), 0, w * h);
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            try {
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(QUALITY);
                ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
                try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
                    writer.setOutput(stream);
                    writer.write(null, new IIOImage(image, null, null), param);
                }
                return out.toByteArray();
            } finally {
                writer.dispose();
            }
        }

        /**
         * H.264: the workers scale the picture (to even sides, which NV12 needs) and turn it into NV12, and Windows'
         * encoder compresses it. A new size starts a new encoder, with a keyframe. Empty if the encoder held the frame
         * back; null if H.264 failed, which switches to JPEG for the rest of the session.
         */
        private byte[] h264Picture(Copy copy, int sw, int sh, long now, boolean forceKeyframe) throws InterruptedException {
            final int cw = copy.width, ch = copy.height;
            final int ow = Math.max(2, sw & ~1), oh = Math.max(2, sh & ~1);
            if (cw < ow || ch < oh) return null; // a picture under 2 pixels: JPEG copes
            final int[] src;
            final int stride;
            if (sw != cw || sh != ch) { // scaled, straight to the even size
                if (scaled.length < ow * oh) scaled = new int[ow * oh];
                final int[] from = copy.pixels, to = scaled;
                inBands(oh, (y0, y1) -> bilinear(from, cw, ch, to, ow, oh, y0, y1));
                src = scaled;
                stride = ow;
            } else { // as it is, less an odd last row or column
                src = copy.pixels;
                stride = cw;
            }
            try {
                boolean fresh = false;
                if (h264 == null || h264.width != ow || h264.height != oh) {
                    closeH264();
                    // About 0.2 bits a pixel: 4.7 Mbit/s for 1024x768 at 30 a second.
                    int bitrate = MathHelper.clamp(ow * oh * fps / 5, 500_000, 8_000_000);
                    h264 = H264Encoder.open(ow, oh, fps, bitrate);
                    fresh = true;
                    DesktopScreens.LOG.info("Sharing: H.264 at {}x{}, {} kbit/s", ow, oh, bitrate / 1000);
                }
                boolean key = fresh || forceKeyframe;
                int bytes = ow * oh * 3 / 2;
                if (nv12.length < bytes) nv12 = new byte[bytes];
                final byte[] planes = nv12;
                inBands(oh, (y0, y1) -> Nv12.fromBgra(src, stride, ow, oh, planes, y0, y1));
                H264Encoder.Frame f = h264.encode(address -> Mem.write(planes, 0, address, bytes),
                        (now - startNanos) / 100, 10_000_000L / fps, key);
                return f == null ? new byte[0] : ShareStream.packH264(f.data, f.keyframe, ow, oh);
            } catch (RuntimeException | LinkageError e) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                DesktopScreens.LOG.error("Sharing: H.264 failed here; JPEG from now on", e);
                h264Failed = true;
                closeH264();
                return null;
            }
        }

        private interface Band {
            void run(int y0, int y1);
        }

        /** Runs {@code band} over rows 0 to {@code rows} in even-height bands, one per worker, and waits for them. */
        private void inBands(int rows, Band band) throws InterruptedException {
            int step = ((rows + STRIPS - 1) / STRIPS + 1) & ~1;
            List<Future<?>> parts = new ArrayList<>();
            for (int y = 0; y < rows; y += step) {
                final int y0 = y, y1 = Math.min(rows, y + step);
                parts.add(workers.submit(() -> band.run(y0, y1)));
            }
            try {
                for (Future<?> f : parts) f.get();
            } catch (ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            }
        }

        private void closeH264() {
            if (h264 != null) {
                h264.close();
                h264 = null;
            }
        }

        /**
         * In pieces the server accepts from a player, labelled with the stream and its screens. Sent from the game's
         * thread: Forge 1.12.2's channel isn't safe to send on from others.
         */
        private void sendFrame(int frame, long taken, byte[] picture) {
            int count = (picture.length + ShareStream.CHUNK_BYTES - 1) / ShareStream.CHUNK_BYTES;
            ShareStream.Label label = new ShareStream.Label(stream, screens);
            List<ShareStream.Chunk> chunks = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int at = i * ShareStream.CHUNK_BYTES;
                chunks.add(new ShareStream.Chunk(label, frame, i, count, taken, Arrays.copyOfRange(picture, at, Math.min(picture.length, at + ShareStream.CHUNK_BYTES))));
            }
            sent = frame;
            mc.addScheduledTask(() -> {
                // Stopped meanwhile (on this thread): its end went out already, and nothing may follow it.
                if (mc.getConnection() == null || !running) return;
                for (ShareStream.Chunk chunk : chunks) Net.CHANNEL.sendToServer(chunk);
            });
        }

        /** Rows {@code y0} to {@code y1} of the scaled picture: each pixel mixes the four nearest (BGRA, 8.8 fixed point). */
        private static void bilinear(int[] src, int sw, int sh, int[] dst, int dw, int dh, int y0, int y1) {
            double fx = sw / (double) dw, fy = sh / (double) dh;
            for (int y = y0; y < y1; y++) {
                double sy = (y + 0.5) * fy - 0.5;
                int ya = MathHelper.clamp((int) sy, 0, sh - 1), yb = Math.min(sh - 1, ya + 1);
                int wy = MathHelper.clamp((int) ((sy - ya) * 256), 0, 256);
                int rowA = ya * sw, rowB = yb * sw, out = y * dw;
                for (int x = 0; x < dw; x++) {
                    double sx = (x + 0.5) * fx - 0.5;
                    int xa = MathHelper.clamp((int) sx, 0, sw - 1), xb = Math.min(sw - 1, xa + 1);
                    int wx = MathHelper.clamp((int) ((sx - xa) * 256), 0, 256);
                    int a = src[rowA + xa], b = src[rowA + xb], c = src[rowB + xa], d = src[rowB + xb];
                    int mixed = 0;
                    for (int shift = 0; shift < 24; shift += 8) {
                        int top = (a >> shift & 255) * (256 - wx) + (b >> shift & 255) * wx;
                        int bottom = (c >> shift & 255) * (256 - wx) + (d >> shift & 255) * wx;
                        mixed |= (top * (256 - wy) + bottom * wy) >> 16 << shift;
                    }
                    dst[out + x] = mixed;
                }
            }
        }

        private static long hash(int[] pixels, int count, int w, int h) {
            long hash = 0xcbf29ce484222325L ^ ((long) w << 32 | h);
            for (int i = 0; i < count; i++) hash = (hash ^ pixels[i]) * 0x100000001b3L;
            return hash;
        }
    }
}
