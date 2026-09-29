package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.ShareStream;
import com.desktopscreens.core.AppWindow;
import com.desktopscreens.core.DesktopAudio;
import com.desktopscreens.core.Win32;
import com.desktopscreens.lib.concentus.OpusApplication;
import com.desktopscreens.lib.concentus.OpusEncoder;
import com.desktopscreens.lib.concentus.OpusException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sends this player's PC sound to the players who hear their shared screens ({@link com.desktopscreens.ShareRelay}),
 * while the Sound switch on the sharing page is on. Each listener hears one screen per owner, the nearest they haven't
 * muted (2026-09-26: never the same sound twice), and gets that screen's sound: its window's
 * program, or for a whole monitor every program but Minecraft and voice chat. So there's one recording per program
 * heard ({@link DesktopAudio}), shared by the screens that play it: two Chrome windows play all of Chrome (Windows
 * records per program, not per window), and every whole monitor plays everything. Opus compresses it: 20 ms a packet,
 * 96 kbit/s stereo. Opus comes from Concentus, which is pure Java, so every player can decode it, whatever their
 * system. Measured on the test PC: 0.6 ms to compress 20 ms, 0.3 to decompress.
 *
 * <p>While the PC is silent, nothing is sent after the first 200 ms; the next sound starts a fresh encoder, and the
 * listener, seeing the gap in the numbers, a fresh decoder.
 */
final class ShareSound {
    private static final int BITRATE = 96_000, COMPLEXITY = 8;
    /** A frame this quiet counts as silence (Windows adds a sample of noise now and then). */
    private static final int SILENT_PEAK = 4;
    private static final int SILENT_FRAMES_BEFORE_PAUSE = 10;
    private static final long PROGRAM_CHECK_NANOS = 1_000_000_000L, STATS_NANOS = 10_000_000_000L;

    // Client thread:
    /** The screens someone hears. */
    private static List<UUID> wanted = List.of();
    private static int maxStreams = 4;
    /** Work out again at once which program each screen plays: the list changed. */
    private static boolean regroup;
    private static long groupedAt;
    /** One per program heard (0: everything). */
    private static final Map<Integer, Recorder> RECORDERS = new LinkedHashMap<>();
    private static int nextStream;
    private static boolean saidTooMany;

    private ShareSound() {}

    /** The server says which screens someone hears. Client thread. */
    static void status(ShareStream.Status status) {
        wanted = status.sound() ? List.copyOf(status.soundScreens()) : List.of();
        maxStreams = Math.max(1, status.limits().maxStreams());
        regroup = true;
    }

    /** Every client tick: starts, points and stops the recordings. */
    static void tick(Minecraft mc) {
        DesktopConfig.load();
        ClientPacketListener connection = mc.getConnection();
        if (mc.level == null) wanted = List.of();
        if (wanted.isEmpty() || !DesktopConfig.shareSound || !Win32.SUPPORTED || connection == null) {
            stopAll();
            return;
        }
        long now = System.nanoTime();
        if (!regroup && now - groupedAt < PROGRAM_CHECK_NANOS) return;
        regroup = false;
        groupedAt = now;
        try {
            // Which program each screen plays now (its window may have closed, or been opened).
            Map<Integer, List<UUID>> byProgram = new LinkedHashMap<>();
            for (UUID id : wanted) {
                AppWindow window = WorldScreens.shown(mc, SourceChoice.of(id)).window();
                int program = window != null && window.alive() ? window.processId() : 0;
                byProgram.computeIfAbsent(program, k -> new ArrayList<>()).add(id);
            }
            // As for pictures: over the server's limit, the recordings already running keep going.
            Map<Integer, List<UUID>> groups = new LinkedHashMap<>();
            for (Integer program : RECORDERS.keySet()) {
                List<UUID> screens = byProgram.get(program);
                if (screens != null) groups.put(program, screens);
            }
            groups.putAll(byProgram);
            if (groups.size() > maxStreams) {
                if (!saidTooMany) {
                    DesktopScreens.LOG.info("Sharing: heard screens play {} different sounds, but the server allows sending {} of them; the others stay silent",
                            groups.size(), maxStreams);
                }
                saidTooMany = true;
                Iterator<Integer> extra = groups.keySet().iterator();
                for (int i = 0; extra.hasNext(); i++) {
                    extra.next();
                    if (i >= maxStreams) extra.remove();
                }
            } else {
                saidTooMany = false;
            }
            for (Iterator<Map.Entry<Integer, Recorder>> it = RECORDERS.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Integer, Recorder> e = it.next();
                if (!groups.containsKey(e.getKey())) {
                    e.getValue().stop();
                    it.remove();
                }
            }
            for (Map.Entry<Integer, List<UUID>> e : groups.entrySet()) {
                Recorder r = RECORDERS.get(e.getKey());
                if (r == null) {
                    r = new Recorder(connection, ++nextStream, e.getKey());
                    RECORDERS.put(e.getKey(), r);
                }
                r.sender.screens = List.copyOf(e.getValue());
            }
        } catch (RuntimeException | LinkageError e) {
            DesktopScreens.LOG.error("Sharing: couldn't record the sound", e);
            wanted = List.of();
            stopAll();
        }
    }

    private static void stopAll() {
        for (Recorder r : RECORDERS.values()) r.stop();
        RECORDERS.clear();
    }

    /** One program's sound (0: everything but the skipped programs) for the screens that play it. Client thread. */
    private static final class Recorder {
        final int stream;
        final Sender sender;
        final DesktopAudio audio;

        Recorder(ClientPacketListener connection, int stream, int program) {
            this.stream = stream;
            sender = new Sender(connection, stream);
            audio = new DesktopAudio(sender, DesktopConfig.soundSkip, message -> DesktopScreens.LOG.info("Sharing, sound stream {}: {}", stream, message));
            audio.setProgram(program);
        }

        void stop() {
            audio.close();
            sender.stop();
            DesktopScreens.LOG.info("Sharing: sound stream {} stopped", stream);
        }
    }

    /** Compresses each frame and sends it. On the recording thread. */
    private static final class Sender implements DesktopAudio.Sink {
        private final ClientPacketListener connection;
        private final int stream;
        private final byte[] packet = new byte[ShareStream.MAX_SOUND_BYTES];
        /** The screens that play it, for the label; set on the client thread. */
        volatile List<UUID> screens = List.of();
        private volatile boolean stopped;
        private OpusEncoder encoder;
        private boolean broken;
        private int seq, silentFrames;
        private long statsFrom = System.nanoTime(), statsBytes, statsWork;
        private int statsSent, statsSilent;

        Sender(ClientPacketListener connection, int stream) {
            this.connection = connection;
            this.stream = stream;
        }

        void stop() {
            stopped = true;
        }

        @Override
        public void frame(short[] pcm, long playedAt) {
            if (stopped || broken) return;
            seq++;
            if (isSilent(pcm)) {
                if (++silentFrames > SILENT_FRAMES_BEFORE_PAUSE) {
                    encoder = null; // the next sound starts afresh; the listener sees the gap in the numbers
                    statsSilent++;
                    stats();
                    return;
                }
            } else {
                silentFrames = 0;
            }
            long start = System.nanoTime();
            int length;
            try {
                if (encoder == null) {
                    encoder = new OpusEncoder(DesktopAudio.RATE, DesktopAudio.CHANNELS, OpusApplication.OPUS_APPLICATION_AUDIO);
                    encoder.setBitrate(BITRATE);
                    encoder.setComplexity(COMPLEXITY);
                }
                length = encoder.encode(pcm, 0, DesktopAudio.FRAME, packet, 0, packet.length);
            } catch (OpusException | RuntimeException e) {
                DesktopScreens.LOG.error("Sharing: couldn't compress the sound; no sound from this stream until the next share", e);
                broken = true;
                return;
            }
            statsWork += System.nanoTime() - start;
            statsBytes += length;
            statsSent++;
            // Sent from here, not the game's thread: the connection hands it to its own thread either way, and this
            // way the sound doesn't wait for the next frame the game draws.
            if (connection.getConnection().isConnected()) {
                ShareStream.Label label = new ShareStream.Label(stream, screens);
                connection.send(new ServerboundCustomPayloadPacket(new ShareStream.SoundUp(label, seq, playedAt, Arrays.copyOf(packet, length))));
            }
            stats();
        }

        private static boolean isSilent(short[] pcm) {
            for (short s : pcm) {
                if (s > SILENT_PEAK || s < -SILENT_PEAK) return false;
            }
            return true;
        }

        private void stats() {
            long now = System.nanoTime();
            if (now - statsFrom < STATS_NANOS) return;
            double seconds = (now - statsFrom) / 1e9;
            DesktopScreens.LOG.info("Sharing sound stream {} ({} screens): {} kbit/s, {} packets a second sent, {} silent ones held back, compressing took {} ms each",
                    stream, screens.size(), String.format("%.1f", statsBytes * 8 / seconds / 1000), String.format("%.1f", statsSent / seconds),
                    String.format("%.1f", statsSilent / seconds), String.format("%.2f", statsWork / 1e6 / Math.max(1, statsSent)));
            statsFrom = now;
            statsBytes = statsWork = 0;
            statsSent = statsSilent = 0;
        }
    }
}
