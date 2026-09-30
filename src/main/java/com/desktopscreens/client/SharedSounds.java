package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.ScreenBlockEntity;
import com.desktopscreens.ShareStream;
import com.desktopscreens.lib.concentus.OpusDecoder;
import com.desktopscreens.lib.concentus.OpusException;
import com.desktopscreens.mixin.SoundEngineAccessor;
import com.desktopscreens.mixin.SoundManagerAccessor;
import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
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
 * <p>The Opus packets are decoded on a thread of their own into a small buffer that Minecraft's sound engine plays
 * through {@link SharedSound}. Minecraft streams music in pieces of a second, four queued, and refills them once a
 * tick: far too late for sound that goes with a live picture. So this stream hands out {@link #CHUNK_FRAMES} (15 ms)
 * at a time and the same thread tops the channel up every 5 ms, which keeps about 60 ms queued in OpenAL. Before the
 * sound starts, and again after the buffer ran dry, it waits for a cushion of sound (60 ms at first, more on a bumpy
 * connection), to ride out uneven arrival.
 */
final class SharedSounds {
    /** Heard up to this far from the nearest shared screen, in blocks; full volume up to {@link #FULL}. */
    static final double RANGE = 24, FULL = 3;
    /** Once heard, kept up to this far, so standing at the edge doesn't switch it on and off. */
    private static final double KEEP = RANGE + 4;
    private static final int RATE = 48000, CHANNELS = 2;
    private static final int CHUNK_FRAMES = RATE * 15 / 1000;
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
    /** With no sound from the owner this long, the sound stops, which frees its channel (Minecraft has only 8). */
    private static final long IDLE_NANOS = 5_000_000_000L;
    private static final long STATS_NANOS = 10_000_000_000L;
    private static final int PUMP_MILLIS = 5, IDLE_PUMP_MILLIS = 50;
    /** The longest an Opus packet can be: 120 ms. */
    private static final int MAX_PACKET_FRAMES = RATE * 120 / 1000;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 16, CHANNELS, true, false);

    private static final Map<UUID, Listening> LISTENING = new ConcurrentHashMap<>();
    private static Map<UUID, Near> near = Map.of(); // client thread
    private static Thread pump;                      // client thread

    private SharedSounds() {}

    /** The nearest of an owner's shared screens: one of its blocks, its id, the nearest point of its picture, how far that is. */
    private record Near(BlockPos anchor, UUID id, Vec3 point, double distance) {}

    /**
     * A packet of someone's sound, from the server. The network thread ({@code ClientboundCustomPayloadPacketMixin}),
     * so the sound keeps flowing while the game draws slowly; the client thread if that ever doesn't catch it.
     */
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
        ClientPacketListener connection = mc.getConnection();
        if (mc.level == null || mc.player == null || connection == null) {
            near = Map.of();
            for (Listening l : LISTENING.values()) l.close(mc);
            LISTENING.clear();
            return;
        }
        DesktopConfig.load();
        // Every tick, so muting, hiding or the owner turning the screen off is heard at once. (Every 5 ticks plus a
        // second's grace took 0.5-1 s, which felt slow in the first two-PC test, 2026-09-25.)
        boolean audible = mc.options.getSoundSourceVolume(SoundSource.MASTER) > 0
                && mc.options.getSoundSourceVolume(SoundSource.RECORDS) > 0;
        near = audible ? scan(mc, connection) : Map.of();
        long now = System.nanoTime();
        for (Map.Entry<UUID, Near> e : near.entrySet()) {
            Listening l = LISTENING.get(e.getKey());
            if (l == null) {
                if (e.getValue().distance() > RANGE) continue; // not near enough to start
                PlayerInfo info = connection.getPlayerInfo(e.getKey());
                l = new Listening(e.getKey(), info != null ? info.getProfile().getName() : "?");
                LISTENING.put(e.getKey(), l);
                startPump();
            }
            l.near = e.getValue();
            if (!e.getValue().id().equals(l.screen)) {
                BlockPos at = e.getValue().anchor();
                DesktopScreens.LOG.info("Shared sound from {}: hearing their screen at {}, {}, {} ({} blocks away)", l.name,
                        at.getX(), at.getY(), at.getZ(), Math.round(e.getValue().distance()));
            }
            l.screen = e.getValue().id();
            ShareWatching.wantSound(new ShareWatching.Screen(e.getKey(), e.getValue().id()), e.getValue().anchor());
        }
        for (Iterator<Listening> it = LISTENING.values().iterator(); it.hasNext(); ) {
            Listening l = it.next();
            if (!near.containsKey(l.owner)) { // muted, hidden, turned off, out of range, the owner gone: stop now
                l.close(mc);
                it.remove();
            } else {
                l.update(mc, now);
            }
        }
    }

    /** Shared screens of players online, not muted or hidden, within {@link #KEEP} blocks: the nearest per owner. */
    private static Map<UUID, Near> scan(Minecraft mc, ClientPacketListener connection) {
        Map<UUID, Near> nearest = new HashMap<>();
        UUID me = mc.player.getUUID();
        Vec3 ear = mc.gameRenderer.getMainCamera().getPosition();
        double reach = KEEP + 2; // a block's middle may be a little further than its glass
        Set<ScreenGroups.Group> seen = new HashSet<>();
        for (Iterator<ScreenBlockEntity> it = ScreenBlockEntity.clientLoaded().iterator(); it.hasNext(); ) {
            ScreenBlockEntity screen = it.next();
            if (screen.getLevel() != mc.level || screen.isRemoved()) { // left behind by a world we left
                it.remove();
                continue;
            }
            UUID owner = screen.owner();
            if (owner == null || owner.equals(me)) continue;
            if (screen.getBlockPos().distToCenterSqr(ear) > reach * reach || connection.getPlayerInfo(owner) == null) continue;
            ScreenGroups.Group group = ScreenGroups.of(mc.level, screen.getBlockPos());
            if (group == null || !seen.add(group) || !group.sharedWith(mc.level, me)) continue;
            // A muted or hidden screen is skipped, so the owner's next nearest one is heard instead.
            if (WatchChoices.muted(mc.level, group) || WatchChoices.hidden(mc.level, group)) continue;
            Vec3 point = group.nearestPoint(ear);
            double distance = point.distanceTo(ear);
            Near best = nearest.get(owner);
            if (distance <= KEEP && (best == null || distance < best.distance())) {
                nearest.put(owner, new Near(group.anchor(), ShareStream.idOf(group.screenId(mc.level)), point, distance));
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
     * Decodes what came in and tops up the channels, every 5 ms. (HotSpot on Windows sleeps with a 1 ms timer when
     * the time isn't a multiple of 10 ms, so this really is about 5 ms.)
     */
    private static void pumpLoop() {
        while (true) {
            for (Listening l : LISTENING.values()) {
                l.decodeWaiting();
                ChannelAccess.ChannelHandle channel = l.channel;
                if (channel != null) channel.execute(Channel::updateStream);
            }
            try {
                Thread.sleep(LISTENING.isEmpty() ? IDLE_PUMP_MILLIS : PUMP_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static Map<SoundInstance, ChannelAccess.ChannelHandle> channels(Minecraft mc) {
        return ((SoundEngineAccessor) ((SoundManagerAccessor) mc.getSoundManager()).desktopscreens$soundEngine()).desktopscreens$channels();
    }

    /** One owner's sound: the packets, the decoded sound waiting to be played, and the sound playing it. */
    private static final class Listening {
        final UUID owner;
        final String name;
        final ConcurrentLinkedQueue<ShareStream.Sound> packets = new ConcurrentLinkedQueue<>();
        volatile long lastPacketAt;
        /** The owner's screen heard now (its id); packets for others are dropped. Set on the client thread. */
        volatile UUID screen;
        volatile ChannelAccess.ChannelHandle channel; // set and cleared on the client thread, used by the pump
        // Client thread only:
        Near near;
        SharedSound sound;
        float volume = -1;
        int restarts;
        long statsFrom = System.nanoTime();
        // Pump thread only:
        OpusDecoder decoder;
        int lastStream, lastSeq;
        final short[] decoded = new short[MAX_PACKET_FRAMES * CHANNELS];
        // Guarded by this: decoded sound waiting for the sound engine, as a ring of stereo frames.
        final short[] ring = new short[LIMIT_FRAMES * CHANNELS];
        int start, count;
        boolean primed;
        int cushion = CUSHION_START;
        /** It ran dry; the next packet tells whether it was late (then the cushion grows) or the owner went quiet. */
        boolean dryPending;
        long calmFrom = System.nanoTime();
        int least = Integer.MAX_VALUE;
        long leastFrom = System.nanoTime();
        int underruns, trimmedFrames, reads;
        long bufferedSum;
        /**
         * For {@link ShareTiming}: where the ring starts in the whole stream (in frames), and where the newest packet
         * starts and when it played on the owner's PC. From these, when any sample in the ring played there.
         */
        long headPos, packetPos, packetPlayed;
        boolean timed;
        /** The last three pieces handed to the sound engine, in frames: what's queued ahead of the next one. */
        final int[] handedOut = new int[3];
        int handedIndex;
        /** The stream of the sound playing now; an older one (its channel still winding down) only gets silence. */
        volatile Feed feed;
        // The sound engine's thread only: what each read hands out, the same buffer every time (OpenAL copies it).
        final ByteBuffer out = BufferUtils.createByteBuffer(CHUNK_FRAMES * CHANNELS * 2);

        Listening(UUID owner, String name) {
            this.owner = owner;
            this.name = name;
        }

        /** Client thread: starts, moves, restarts and stops the sound. */
        void update(Minecraft mc, long now) {
            float target = volumeAt(near.distance());
            volume = volume < 0 ? target : volume + (target - volume) * 0.3f; // smoothly, not in steps every 5 ticks
            if (sound != null && (channel == null || channel.isStopped() || channels(mc).get(sound) != channel)) {
                // Gone: it ran dry (the game stalled longer than the queue), the sound engine restarted, the volume
                // went to 0... It starts again once there's enough sound.
                channel = null;
                sound = null;
                restarts++;
            }
            boolean flowing = now - lastPacketAt < IDLE_NANOS;
            if (sound == null && flowing && readyToStart()) {
                feed = new Feed();
                SharedSound s = new SharedSound(feed, near.point(), volume);
                mc.getSoundManager().play(s);
                ChannelAccess.ChannelHandle handle = channels(mc).get(s);
                if (handle != null) { // else no free channel, or the engine is off: try again next tick
                    sound = s;
                    channel = handle;
                }
            } else if (sound != null && !flowing) {
                stopSound(mc);
            } else if (sound != null) {
                sound.move(near.point(), volume);
            }
            if (now - statsFrom > STATS_NANOS) stats(now);
        }

        private void stopSound(Minecraft mc) {
            if (sound == null) return;
            channel = null; // first, so the pump stops refilling it
            sound.finish();
            mc.getSoundManager().stop(sound);
            sound = null;
        }

        void close(Minecraft mc) {
            stopSound(mc);
            DesktopScreens.LOG.info("Shared sound from {}: stopped", name);
        }

        private synchronized boolean readyToStart() {
            if (count < cushion) return false;
            primed = false; // the new sound's first read primes it again
            return true;
        }

        private void stats(long now) {
            int u, t, r, c;
            long sum;
            synchronized (this) {
                u = underruns;
                t = trimmedFrames;
                r = reads;
                sum = bufferedSum;
                c = cushion;
                underruns = trimmedFrames = reads = 0;
                bufferedSum = 0;
            }
            if (sound != null || u > 0 || restarts > 0) {
                DesktopScreens.LOG.info("Shared sound from {}: about {} ms waiting here (cushion {} ms) plus {} ms queued in the sound engine, "
                                + "volume {}%, ran dry {} times (packets late), {} ms cut to catch up, restarted {} times",
                        name, r == 0 ? 0 : sum / r * 1000 / RATE, c * 1000 / RATE, 4 * CHUNK_FRAMES * 1000 / RATE,
                        Math.round(volume * 100), u, t * 1000 / RATE, restarts);
            }
            restarts = 0;
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

        /** What one sound plays. */
        final class Feed implements AudioStream {
            @Override
            public AudioFormat getFormat() {
                return FORMAT;
            }

            @Override
            public ByteBuffer read(int size) {
                return next(this == feed);
            }

            @Override
            public void close() {
                // Nothing to free: the sound engine calls this when the sound ends.
            }
        }

        /** The sound engine's thread: the next piece to queue, or silence until there's enough. Never empty. */
        private ByteBuffer next(boolean current) {
            out.clear();
            int n = 0;
            if (current) {
                synchronized (this) {
                    reads++;
                    bufferedSum += count;
                    least = Math.min(least, count);
                    if (!primed && count >= cushion) primed = true;
                    if (primed && count == 0) { // ran dry: wait for enough again
                        primed = false;
                        dryPending = true;
                    }
                    n = primed ? Math.min(count, CHUNK_FRAMES) : 0;
                    for (int i = 0, at = start; i < n; i++) {
                        out.putShort(ring[at * 2]).putShort(ring[at * 2 + 1]);
                        at = at + 1 == LIMIT_FRAMES ? 0 : at + 1;
                    }
                    if (n > 0 && timed) {
                        // Heard once what's queued ahead has played: the last three pieces, less about half of the
                        // one playing now.
                        long ahead = handedOut[0] + handedOut[1] + handedOut[2] - CHUNK_FRAMES / 2;
                        long played = packetPlayed + (headPos - packetPos) * 1_000_000_000L / RATE;
                        ShareTiming.soundHeard(owner, played, System.nanoTime() + ahead * 1_000_000_000L / RATE);
                    }
                    handedOut[handedIndex] = n > 0 ? n : CHUNK_FRAMES;
                    handedIndex = (handedIndex + 1) % handedOut.length;
                    drop(n);
                }
            }
            if (n == 0) {
                for (int i = 0; i < CHUNK_FRAMES * CHANNELS; i++) out.putShort((short) 0);
            }
            return out.flip();
        }
    }
}
