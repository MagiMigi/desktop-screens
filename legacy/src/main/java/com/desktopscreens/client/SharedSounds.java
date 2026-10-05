package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.ScreenBlockEntity;
import com.desktopscreens.ShareStream;
import com.desktopscreens.lib.concentus.OpusDecoder;
import com.desktopscreens.lib.concentus.OpusException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.audio.SoundEventAccessor;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiSubtitleOverlay;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The sound of screens other players share with this player ({@link ShareSound} on their side). One per player: it
 * plays while one of their shared screens is within {@link #RANGE} blocks, the nearest one, and it's what that screen
 * plays (its window's program, or everything for a whole monitor), so the same sound is never heard twice
 * (2026-09-26). Full volume up to {@link #FULL} blocks away, then fading to nothing at {@link #RANGE}, on the
 * Jukebox/Note Blocks slider. Stereo, so it doesn't come from a direction: keeping the video's left and
 * right mattered more (2026-09-24). Muted and hidden screens are skipped, so the next nearest one is heard
 * ({@link WatchChoices}).
 *
 * <p>The Opus packets are decoded on a thread of their own into a small buffer, and the same thread tops up the sound
 * output every 5 ms, {@link #CHUNK_FRAMES} (15 ms) at a time, keeping {@link #QUEUED_FRAMES} (60 ms) queued there.
 * Before the sound starts, and again after the buffer ran dry, it waits for a cushion of sound (60 ms at first, more
 * on a bumpy connection), to ride out uneven arrival.
 *
 * <p>On 1.12.2 it plays through Java's own sound output (Java Sound, a line of its own on the default device), not
 * Minecraft's sound engine (paulscode's SoundSystem on LWJGL 2's OpenAL), so the volume is worked out here (master x
 * Jukebox/Note Blocks x distance) and applied to the samples. Minecraft streams music in pieces it refills once a
 * tick, far too late for sound that goes with a live picture. An OpenAL source of our own, topped up every frame the
 * game draws, ran out whenever a frame took longer than its 60 ms (Test 29, 2026-10-03: 1-3 times every 10 s); and
 * topping it up from a thread of our own could crash the game: LWJGL 2 resets OpenAL's functions when Minecraft
 * restarts its sound engine (F3+T, resource packs), without any lock to wait for.
 */
final class SharedSounds {
    /** Heard up to this far from the nearest shared screen, in blocks; full volume up to {@link #FULL}. */
    static final double RANGE = 24, FULL = 3;
    /** Once heard, kept up to this far, so standing at the edge doesn't switch it on and off. */
    private static final double KEEP = RANGE + 4;
    private static final int RATE = 48000, CHANNELS = 2, FRAME_BYTES = CHANNELS * 2;
    private static final int CHUNK_FRAMES = RATE * 15 / 1000;
    /** Kept queued in the sound output. */
    private static final int QUEUED_FRAMES = 4 * CHUNK_FRAMES;
    /** The sound output's own buffer: room for what's queued and more, as Java's line reports it in 10 ms steps. */
    private static final int LINE_FRAMES = RATE * 150 / 1000;
    /**
     * The cushion: how much sound waits here before playing starts (and again after running dry), against packets
     * arriving unevenly. It grows by a step each time the sound runs dry and shrinks by half a step after every
     * {@link #CALM_NANOS} without. The first test (2026-09-24, two games and the server on one PC) ran dry about
     * every 10 s with a fixed 50 ms; the second, shrinking every 10 s, about every 25 s, the cushion going up and down
     * between 40 and 90 ms: late packets came every 20-40 s. So it shrinks only after 30 calm seconds.
     */
    private static final int CUSHION_START = RATE * 60 / 1000, CUSHION_MIN = RATE * 40 / 1000, CUSHION_MAX = RATE * 150 / 1000;
    private static final int CUSHION_STEP = RATE * 20 / 1000;
    private static final long CALM_NANOS = 30_000_000_000L;
    /** At most this much waits here; beyond it the oldest goes, so a stall never leaves the sound behind for good. */
    private static final int LIMIT_FRAMES = RATE * 300 / 1000;
    /** Over this long, never below the cushion means the delay is needlessly long: it's cut to half the cushion. */
    private static final long TRIM_NANOS = 2_000_000_000L;
    /** With no sound from the owner this long, the sound stops and its line closes. */
    private static final long IDLE_NANOS = 5_000_000_000L;
    /** After the sound output couldn't be opened, the next try. */
    private static final long RETRY_NANOS = 5_000_000_000L;
    private static final long STATS_NANOS = 10_000_000_000L;
    /** The subtitle fades 3 s after it was last said: said again every second while the sound plays. */
    private static final long SUBTITLE_NANOS = 1_000_000_000L;
    private static final int PUMP_MILLIS = 5, IDLE_PUMP_MILLIS = 50;
    /** The longest an Opus packet can be: 120 ms. */
    private static final int MAX_PACKET_FRAMES = RATE * 120 / 1000;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 16, CHANNELS, true, false);
    private static final ResourceLocation ID = new ResourceLocation(DesktopScreens.MOD_ID, "shared_screen");

    private static final Map<UUID, Listening> LISTENING = new ConcurrentHashMap<>();
    /** Let go of on the client thread: the pump closes their lines. */
    private static final ConcurrentLinkedQueue<Listening> CLOSING = new ConcurrentLinkedQueue<>();
    private static Map<UUID, Near> near = Collections.emptyMap(); // client thread
    private static Thread pump;                                    // client thread
    private static boolean subtitlesLooked;                        // client thread
    private static Field subtitles;

    private SharedSounds() {}

    /** The nearest of an owner's shared screens: one of its blocks, its id, the nearest point of its picture, how far that is. */
    private static final class Near {
        final BlockPos anchor;
        final UUID id;
        final Vec3d point;
        final double distance;

        Near(BlockPos anchor, UUID id, Vec3d point, double distance) {
            this.anchor = anchor;
            this.id = id;
            this.point = point;
            this.distance = distance;
        }
    }

    /** A packet of someone's sound, from the server. The network thread, so the sound keeps flowing while the game draws slowly. */
    static void sound(ShareStream.Sound packet) {
        Listening l = LISTENING.get(packet.owner());
        if (l == null) return; // not near any more
        // Only the screen we hear now: after walking to another of the owner's screens, the one before may still send
        // for a moment, and two streams at once would be heard twice.
        if (!packet.screens().contains(l.screen)) return;
        l.packets.add(packet);
        l.lastPacketAt = System.nanoTime();
    }

    /** Every client tick: whose sound to hear, from where, how loud. */
    static void tick(Minecraft mc) {
        NetHandlerPlayClient connection = mc.getConnection();
        if (mc.world == null || mc.player == null || connection == null) {
            near = Collections.emptyMap();
            for (Listening l : LISTENING.values()) l.close();
            LISTENING.clear();
            return;
        }
        DesktopConfig.load();
        // Every tick, so muting, hiding or the owner turning the screen off is heard at once. (Every 5 ticks plus a
        // second's grace took 0.5-1 s, which felt slow in the first two-PC test, 2026-09-25.)
        float master = mc.gameSettings.getSoundLevel(SoundCategory.MASTER), records = mc.gameSettings.getSoundLevel(SoundCategory.RECORDS);
        near = master > 0 && records > 0 ? scan(mc, connection) : Collections.emptyMap();
        long now = System.nanoTime();
        for (Map.Entry<UUID, Near> e : near.entrySet()) {
            Listening l = LISTENING.get(e.getKey());
            if (l == null) {
                if (e.getValue().distance > RANGE) continue; // not near enough to start
                NetworkPlayerInfo info = connection.getPlayerInfo(e.getKey());
                l = new Listening(e.getKey(), info != null ? info.getGameProfile().getName() : "?");
                LISTENING.put(e.getKey(), l);
                startPump();
            }
            l.near = e.getValue();
            if (!e.getValue().id.equals(l.screen)) {
                BlockPos at = e.getValue().anchor;
                DesktopScreens.LOG.info("Shared sound from {}: hearing their screen at {}, {}, {} ({} blocks away)", l.name,
                        at.getX(), at.getY(), at.getZ(), Math.round(e.getValue().distance));
            }
            l.screen = e.getValue().id;
            ShareWatching.wantSound(new ShareWatching.Screen(e.getKey(), e.getValue().id), e.getValue().anchor);
        }
        for (Iterator<Listening> it = LISTENING.values().iterator(); it.hasNext(); ) {
            Listening l = it.next();
            if (!near.containsKey(l.owner)) { // muted, hidden, turned off, out of range, the owner gone: stop now
                l.close();
                it.remove();
            } else {
                l.update(mc, master * records, now);
            }
        }
    }

    /** Shared screens of players online, not muted or hidden, within {@link #KEEP} blocks: the nearest per owner. */
    private static Map<UUID, Near> scan(Minecraft mc, NetHandlerPlayClient connection) {
        Map<UUID, Near> nearest = new HashMap<>();
        Entity view = mc.getRenderViewEntity();
        if (view == null) return nearest;
        UUID me = mc.player.getUniqueID();
        Vec3d ear = view.getPositionEyes(1f);
        double reach = KEEP + 2; // a block's middle may be a little further than its glass
        Set<ScreenGroups.Group> seen = new HashSet<>();
        for (Iterator<ScreenBlockEntity> it = ScreenBlockEntity.clientLoaded().iterator(); it.hasNext(); ) {
            ScreenBlockEntity screen = it.next();
            if (screen.getWorld() != mc.world || screen.isInvalid()) { // left behind by a world we left
                it.remove();
                continue;
            }
            UUID owner = screen.owner();
            if (owner == null || owner.equals(me)) continue;
            if (screen.getPos().distanceSqToCenter(ear.x, ear.y, ear.z) > reach * reach || connection.getPlayerInfo(owner) == null) continue;
            ScreenGroups.Group group = ScreenGroups.of(mc.world, screen.getPos());
            if (group == null || !seen.add(group) || !group.sharedWith(mc.world, me)) continue;
            // A muted or hidden screen is skipped, so the owner's next nearest one is heard instead.
            if (WatchChoices.muted(mc.world, group) || WatchChoices.hidden(mc.world, group)) continue;
            Vec3d point = group.nearestPoint(ear);
            double distance = point.distanceTo(ear);
            Near best = nearest.get(owner);
            if (distance <= KEEP && (best == null || distance < best.distance)) {
                nearest.put(owner, new Near(group.anchor(), ShareStream.idOf(group.screenId(mc.world)), point, distance));
            }
        }
        return nearest;
    }

    private static float volumeAt(double distance) {
        if (distance <= FULL) return 1;
        return (float) Math.max(0, 1 - (distance - FULL) / (RANGE - FULL));
    }

    private static void startPump() {
        if (pump != null) return;
        pump = new Thread(SharedSounds::pumpLoop, "Desktop Screens shared sound");
        pump.setDaemon(true);
        pump.start();
    }

    /**
     * Decodes what came in and tops up the sound output, every 5 ms. (HotSpot on Windows sleeps with a 1 ms timer
     * when the time isn't a multiple of 10 ms, so this really is about 5 ms.)
     */
    private static void pumpLoop() {
        while (true) {
            for (Listening l; (l = CLOSING.poll()) != null; ) l.closeLine();
            for (Listening l : LISTENING.values()) {
                l.decodeWaiting();
                l.feed();
            }
            try {
                Thread.sleep(LISTENING.isEmpty() ? IDLE_PUMP_MILLIS : PUMP_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** One owner's sound: the packets, the decoded sound waiting to be played, and the line playing it. */
    private static final class Listening {
        final UUID owner;
        final String name;
        final ConcurrentLinkedQueue<ShareStream.Sound> packets = new ConcurrentLinkedQueue<>();
        volatile long lastPacketAt;
        /** The owner's screen heard now (its id); packets for others are dropped. Set on the client thread. */
        volatile UUID screen;
        /** Set on the client thread: whether it should play now, how loud, and that it's let go of. */
        volatile boolean playing, closed;
        volatile float gain;
        /** The line is open and playing. Set by the pump. */
        volatile boolean sounding;
        // Client thread only:
        Near near;
        float volume = -1;
        long statsFrom = System.nanoTime(), subtitleAt;
        // Pump thread only:
        OpusDecoder decoder;
        int lastStream, lastSeq;
        final short[] decoded = new short[MAX_PACKET_FRAMES * CHANNELS];
        SourceDataLine line;
        /** Frames written to the line since it opened. */
        long written;
        long failedAt;
        boolean saidFailure;
        /** The gain the last piece ended at, so a change goes smoothly across the next. */
        float appliedGain = -1;
        final byte[] out = new byte[CHUNK_FRAMES * FRAME_BYTES];
        // Guarded by this: decoded sound waiting to be played, as a ring of stereo frames.
        final short[] ring = new short[LIMIT_FRAMES * CHANNELS];
        int start, count;
        boolean primed;
        int cushion = CUSHION_START;
        /** It ran dry; the next packet tells whether it was late (then the cushion grows) or the owner went quiet. */
        boolean dryPending;
        long calmFrom = System.nanoTime();
        int least = Integer.MAX_VALUE;
        long leastFrom = System.nanoTime();
        int underruns, trimmedFrames, reads, ranOut, lineReads;
        long bufferedSum, lineSum;
        /**
         * For {@link ShareTiming}: where the ring starts in the whole stream (in frames), and where the newest packet
         * starts and when it played on the owner's PC. From these, when any sample in the ring played there.
         */
        long headPos, packetPos, packetPlayed;
        boolean timed;

        Listening(UUID owner, String name) {
            this.owner = owner;
            this.name = name;
        }

        /** Client thread, every tick: starts and stops the sound, and sets its volume. */
        void update(Minecraft mc, float settings, long now) {
            float target = volumeAt(near.distance);
            volume = volume < 0 ? target : volume + (target - volume) * 0.3f; // smoothly, not in steps
            gain = volume * settings;
            boolean flowing = now - lastPacketAt < IDLE_NANOS;
            if (!playing && flowing && readyToStart()) playing = true;
            else if (playing && !flowing) playing = false;
            if (sounding && mc.gameSettings.showSubtitles && now - subtitleAt > SUBTITLE_NANOS) {
                subtitleAt = now;
                subtitle(mc);
            }
            if (now - statsFrom > STATS_NANOS) stats(now);
        }

        /** "Shared screen plays" in the subtitles, with the arrow towards the screen. */
        private void subtitle(Minecraft mc) {
            if (!subtitlesLooked) {
                subtitlesLooked = true;
                try {
                    // Minecraft's own name in the dev game, the SRG name in a real one.
                    subtitles = ReflectionHelper.findField(GuiIngame.class, "overlaySubtitle", "field_184049_t");
                } catch (RuntimeException e) {
                    DesktopScreens.LOG.warn("Shared sound: the subtitles not found ({}); no subtitle for shared screens", e.toString());
                }
            }
            if (subtitles == null || mc.ingameGUI == null) return;
            try {
                GuiSubtitleOverlay overlay = (GuiSubtitleOverlay) subtitles.get(mc.ingameGUI);
                if (overlay == null) return;
                Vec3d p = near.point;
                overlay.soundPlay(new PositionedSoundRecord(ID, SoundCategory.RECORDS, 1, 1, false, 0, ISound.AttenuationType.NONE,
                        (float) p.x, (float) p.y, (float) p.z), new SoundEventAccessor(ID, "desktopscreens.subtitle.shared_screen"));
            } catch (IllegalAccessException | RuntimeException e) {
                subtitles = null;
                DesktopScreens.LOG.warn("Shared sound: couldn't show the subtitle ({}); no subtitle for shared screens", e.toString());
            }
        }

        /** Client thread: let go of. The pump closes its line. */
        void close() {
            closed = true;
            playing = false;
            CLOSING.add(this);
            DesktopScreens.LOG.info("Shared sound from {}: stopped", name);
        }

        private synchronized boolean readyToStart() {
            if (count < cushion) return false;
            primed = false; // the new sound's first piece primes it again
            return true;
        }

        private void stats(long now) {
            int u, t, r, c, o, lr;
            long sum, lineTotal;
            synchronized (this) {
                u = underruns;
                t = trimmedFrames;
                r = reads;
                sum = bufferedSum;
                c = cushion;
                o = ranOut;
                lr = lineReads;
                lineTotal = lineSum;
                underruns = trimmedFrames = reads = ranOut = lineReads = 0;
                bufferedSum = lineSum = 0;
            }
            if (sounding || u > 0 || o > 0) {
                DesktopScreens.LOG.info("Shared sound from {}: about {} ms waiting here (cushion {} ms) plus {} ms in the sound output, "
                                + "volume {}%, ran dry {} times (packets late), {} ms cut to catch up, ran out {} times (this game or PC stalled)",
                        name, r == 0 ? 0 : sum / r * 1000 / RATE, c * 1000 / RATE, lr == 0 ? 0 : lineTotal / lr * 1000 / RATE,
                        Math.round(volume * 100), u, t * 1000 / RATE, o);
            }
            statsFrom = now;
        }

        /**
         * Pump thread: decodes the packets that came in. A gap in their numbers, or another stream (a screen playing
         * another program), means a fresh start.
         */
        void decodeWaiting() {
            for (ShareStream.Sound p; (p = packets.poll()) != null; ) {
                try {
                    boolean continues = decoder != null && p.stream() == lastStream && p.seq() == lastSeq + 1;
                    if (!continues) decoder = new OpusDecoder(RATE, CHANNELS);
                    lastStream = p.stream();
                    lastSeq = p.seq();
                    int frames = decoder.decode(p.opus(), 0, p.opus().length, decoded, 0, MAX_PACKET_FRAMES, false);
                    if (frames > 0) add(decoded, frames, continues, p.played());
                } catch (OpusException | RuntimeException e) {
                    decoder = null; // a broken packet: start afresh with the next
                }
            }
        }

        /** Pump thread: opens, tops up and closes the line. */
        void feed() {
            if (closed || !playing) {
                closeLine();
                return;
            }
            if (line == null && !openLine()) return;
            int queued = (line.getBufferSize() - line.available()) / FRAME_BYTES;
            if (sounding && queued == 0) {
                synchronized (this) {
                    ranOut++; // nothing topped it up for longer than what was queued
                }
            }
            // Written but not heard yet: what's queued, and the part of the device's own buffer Java's line counts.
            long ahead = written - line.getLongFramePosition();
            while (queued < QUEUED_FRAMES) {
                int n = next(ahead);
                line.write(out, 0, n * FRAME_BYTES);
                written += n;
                ahead += n;
                queued += n;
            }
            if (!sounding) { // just opened: start with the queue full
                line.start();
                sounding = true;
            }
        }

        private boolean openLine() {
            long now = System.nanoTime();
            if (failedAt != 0 && now - failedAt < RETRY_NANOS) return false;
            try {
                line = AudioSystem.getSourceDataLine(FORMAT);
                line.open(FORMAT, LINE_FRAMES * FRAME_BYTES);
                written = 0;
                appliedGain = -1;
                failedAt = 0;
                return true;
            } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
                line = null;
                failedAt = now;
                if (!saidFailure) DesktopScreens.LOG.warn("Shared sound: no sound output to play it on ({})", e.toString());
                saidFailure = true;
                return false;
            }
        }

        void closeLine() {
            sounding = false;
            if (line == null) return;
            line.stop();
            line.flush();
            line.close();
            line = null;
        }

        /**
         * {@code continues}: this packet follows the last one, so if the sound ran dry before it, it came late.
         * {@code played}: when it played on the owner's PC (their clock).
         */
        private synchronized void add(short[] pcm, int frames, boolean continues, long played) {
            long now = System.nanoTime();
            if (dryPending) {
                dryPending = false;
                if (continues) { // late, not the owner's PC going quiet: keep more
                    underruns++;
                    cushion = Math.min(CUSHION_MAX, cushion + CUSHION_STEP);
                    calmFrom = now;
                }
            } else if (now - calmFrom > CALM_NANOS) {
                cushion = Math.max(CUSHION_MIN, cushion - CUSHION_STEP / 2);
                calmFrom = now;
            }
            if (count + frames > LIMIT_FRAMES) { // far behind: the oldest goes
                int drop = count + frames - LIMIT_FRAMES;
                drop(drop);
                trimmedFrames += drop;
            }
            packetPos = headPos + count;
            packetPlayed = played;
            timed = true;
            int at = (start + count) % LIMIT_FRAMES;
            for (int i = 0; i < frames; i++) {
                ring[at * 2] = pcm[i * 2];
                ring[at * 2 + 1] = pcm[i * 2 + 1];
                at = at + 1 == LIMIT_FRAMES ? 0 : at + 1;
            }
            count += frames;
            if (now - leastFrom > TRIM_NANOS) {
                // Never below the cushion over the last 2 s: more than that is only delay. Half the cushion stays.
                if (primed && least != Integer.MAX_VALUE && least > cushion) {
                    int cut = Math.min(count, least - cushion / 2);
                    drop(cut);
                    trimmedFrames += cut;
                }
                least = Integer.MAX_VALUE;
                leastFrom = now;
            }
        }

        private void drop(int frames) {
            start = (start + frames) % LIMIT_FRAMES;
            count -= frames;
            headPos += frames;
        }

        /**
         * Pump thread: the next piece into {@link #out}, at the volume asked for, or silence until there's enough.
         * Never empty. {@code ahead}: frames written to the line and not heard yet. Returns its length in frames.
         */
        private int next(long ahead) {
            int n;
            float from = appliedGain < 0 ? gain : appliedGain, to = gain;
            appliedGain = to;
            synchronized (this) {
                reads++;
                bufferedSum += count;
                least = Math.min(least, count);
                // Java's line says where it is in 10 ms steps, and nonsense for a moment after running out.
                boolean plausible = ahead >= 0 && ahead <= LINE_FRAMES * 2L;
                if (plausible) {
                    lineReads++;
                    lineSum += ahead;
                }
                if (!primed && count >= cushion) primed = true;
                if (primed && count == 0) { // ran dry: wait for enough again
                    primed = false;
                    dryPending = true;
                }
                n = primed ? Math.min(count, CHUNK_FRAMES) : 0;
                for (int i = 0, at = start; i < n; i++) {
                    // Over the piece from the last volume to the new one, so a change makes no click.
                    float g = from + (to - from) * i / n;
                    put(i * 2, ring[at * 2], g);
                    put(i * 2 + 1, ring[at * 2 + 1], g);
                    at = at + 1 == LIMIT_FRAMES ? 0 : at + 1;
                }
                if (n > 0 && timed && plausible) {
                    long played = packetPlayed + (headPos - packetPos) * 1_000_000_000L / RATE;
                    ShareTiming.soundHeard(owner, played, System.nanoTime() + ahead * 1_000_000_000L / RATE);
                }
                drop(n);
            }
            if (n == 0) { // silence
                n = CHUNK_FRAMES;
                Arrays.fill(out, 0, n * FRAME_BYTES, (byte) 0);
            }
            return n;
        }

        /** One 16-bit sample into {@link #out}, little-endian, at {@code gain} (at most 1, so it never clips). */
        private void put(int sample, short value, float gain) {
            int v = Math.round(value * gain);
            out[sample * 2] = (byte) v;
            out[sample * 2 + 1] = (byte) (v >> 8);
        }
    }
}
