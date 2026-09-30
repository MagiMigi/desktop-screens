package com.desktopscreens.client;

import com.desktopscreens.ShareStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Tells the server what this player wants of each screen shared with them ({@link ShareStream.Watch}): its pictures
 * while it's drawn ({@link SharedPictures}), its sound while it's the owner's nearest one we haven't muted or hidden
 * ({@link SharedSounds}).
 * Each screen shows its own thing, so this goes per screen. Said again every 2 s while it holds; pictures stop 3 s
 * after the last time the screen was drawn (so turning around doesn't restart them), sound 1 s after leaving its range.
 * Client thread only.
 */
final class ShareWatching {
    private static final long KEEP_ALIVE_NANOS = 2_000_000_000L;
    private static final long PICTURES_LINGER_NANOS = 3_000_000_000L, SOUND_LINGER_NANOS = 1_000_000_000L;

    /** An owner's screen: its id ({@link ShareStream#idOf}). */
    record Screen(UUID owner, UUID id) {}

    private static final Map<Screen, Interest> INTERESTS = new HashMap<>();

    private ShareWatching() {}

    private static final class Interest {
        BlockPos pos;              // one of its blocks, for the server's checks
        BlockPos saidPos;          // the one the server heard last, which it goes by
        long picturesAt, soundAt, saidAt;
        boolean saidPictures, saidSound;
    }

    /** The renderer drew {@code screen}, which is shared with us; {@code pos} is one of its blocks. */
    static void wantPictures(Screen screen, BlockPos pos) {
        Interest i = INTERESTS.computeIfAbsent(screen, k -> new Interest());
        i.pos = pos;
        i.picturesAt = System.nanoTime();
    }

    /**
     * We hear {@code screen}, shared with us: its owner's nearest one that isn't muted or hidden. Only one per owner, so
     * the one heard before stops at once (not after the usual second), and its sound isn't sent for nothing.
     */
    static void wantSound(Screen screen, BlockPos pos) {
        long now = System.nanoTime();
        for (Map.Entry<Screen, Interest> e : INTERESTS.entrySet()) {
            Interest other = e.getValue();
            if (e.getKey().owner().equals(screen.owner()) && !e.getKey().equals(screen) && now - other.soundAt < SOUND_LINGER_NANOS) {
                other.soundAt = now - SOUND_LINGER_NANOS - 1;
            }
        }
        Interest i = INTERESTS.computeIfAbsent(screen, k -> new Interest());
        i.pos = pos;
        i.soundAt = now;
    }

    /** We no longer want pictures of {@code screen} (hidden): the server hears it on the next tick. */
    static void stopPictures(Screen screen) {
        Interest i = INTERESTS.get(screen);
        if (i != null) i.picturesAt = System.nanoTime() - PICTURES_LINGER_NANOS - 1;
    }

    /** Whether we still want pictures of {@code screen}: it was drawn a moment ago. */
    static boolean wantsPictures(Screen screen) {
        Interest i = INTERESTS.get(screen);
        return i != null && System.nanoTime() - i.picturesAt < PICTURES_LINGER_NANOS;
    }

    /** Every client tick: tells the server about changes, and again every 2 s. */
    static void tick(Minecraft mc) {
        ClientPacketListener connection = mc.getConnection();
        if (mc.level == null || connection == null) {
            INTERESTS.clear();
            return;
        }
        long now = System.nanoTime();
        for (Iterator<Map.Entry<Screen, Interest>> it = INTERESTS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Screen, Interest> e = it.next();
            Interest i = e.getValue();
            boolean pictures = now - i.picturesAt < PICTURES_LINGER_NANOS, sound = now - i.soundAt < SOUND_LINGER_NANOS;
            // No more pictures for it from here on: whatever it showed is stale. (A hidden screen shown again kept its
            // old stream's last picture up, frozen, when that stream's end never reached us, 2026-09-26.)
            if (!pictures && i.saidPictures) SharedPictures.forget(e.getKey());
            if (!pictures && !sound) {
                if (i.saidPos != null) connection.send(new ServerboundCustomPayloadPacket(new ShareStream.Watch(i.saidPos, false, false, false)));
                it.remove();
            } else if (pictures != i.saidPictures || sound != i.saidSound || now - i.saidAt > KEEP_ALIVE_NANOS) {
                connection.send(new ServerboundCustomPayloadPacket(new ShareStream.Watch(i.pos, pictures, sound, H264Support.canDecode())));
                i.saidPos = i.pos;
                i.saidPictures = pictures;
                i.saidSound = sound;
                i.saidAt = now;
            }
        }
    }
}
