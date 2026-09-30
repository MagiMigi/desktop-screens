package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How late a shared screen's pictures and sound come, for the log every 10 s. The owner stamps both with their own
 * clock ({@link System#nanoTime}): when a picture was taken, when a sound played on their PC. Here each is compared
 * with when it's shown or heard. On two PCs the clocks differ, so only the difference between the two delays means
 * something: how far the sound is behind the picture. With both games on one PC the clock is the same, so the delays
 * themselves are real too. While the sound plays, {@link SharedPictures} holds the pictures back by the sound's delay
 * ({@link #soundDelay}), so the two line up and the difference logged should be near 0.
 */
final class ShareTiming {
    private static final long LOG_NANOS = 10_000_000_000L, PLAUSIBLE_NANOS = 10_000_000_000L;
    private static final Map<UUID, Timing> BY_OWNER = new ConcurrentHashMap<>();
    private static long loggedAt = System.nanoTime();

    private ShareTiming() {}

    /** Sound heard within this long counts as playing, for holding the pictures back. */
    private static final long SOUND_FRESH_NANOS = 1_000_000_000L;
    /**
     * {@link #soundDelay} while no sound plays. Not -1: on two PCs the delay includes the difference between their
     * clocks, which is negative when this PC was started later. The first two-PC test (2026-09-25, a laptop started
     * that day) took that for "no sound", so the pictures weren't held back: the sound came 65-93 ms after them.
     */
    static final long NO_SOUND = Long.MIN_VALUE;

    /** Guarded by itself. */
    private static final class Timing {
        long pictureSum, soundSum;
        int pictures, sounds;
        /** The sound's delay right now, smoothed over about half a second, and when sound was last heard. */
        long soundDelay, soundAt;
    }

    /**
     * How late {@code owner}'s sound is heard right now (this game's clock minus theirs, so it also holds on two
     * PCs, and may be negative there), or {@link #NO_SOUND} while none is playing. {@link SharedPictures} holds
     * pictures back by it.
     */
    static long soundDelay(UUID owner) {
        Timing t = BY_OWNER.get(owner);
        if (t == null) return NO_SOUND;
        synchronized (t) {
            return System.nanoTime() - t.soundAt < SOUND_FRESH_NANOS ? t.soundDelay : NO_SOUND;
        }
    }

    /** A picture taken at {@code taken} (the owner's clock) goes up to the screen now. Render thread. */
    static void pictureShown(UUID owner, long taken) {
        record(owner, System.nanoTime() - taken, true);
    }

    /** Sound that played on the owner's PC at {@code played} will be heard at {@code heardAt}. The sound engine's thread. */
    static void soundHeard(UUID owner, long played, long heardAt) {
        record(owner, heardAt - played, false);
    }

    private static void record(UUID owner, long delay, boolean picture) {
        Timing t = BY_OWNER.computeIfAbsent(owner, k -> new Timing());
        synchronized (t) {
            if (picture) {
                t.pictureSum += delay;
                t.pictures++;
            } else {
                t.soundSum += delay;
                t.sounds++;
                long now = System.nanoTime();
                // About 66 pieces a second: 1/32 of the way each time follows a change within half a second.
                t.soundDelay = now - t.soundAt < SOUND_FRESH_NANOS ? t.soundDelay + (delay - t.soundDelay) / 32 : delay;
                t.soundAt = now;
            }
        }
    }

    /** Every client tick: every 10 s, a line per owner whose pictures or sound came meanwhile. */
    static void tick(Minecraft mc) {
        long now = System.nanoTime();
        if (now - loggedAt < LOG_NANOS) return;
        loggedAt = now;
        for (Map.Entry<UUID, Timing> e : BY_OWNER.entrySet()) {
            long pictureSum, soundSum;
            int pictures, sounds;
            Timing t = e.getValue();
            synchronized (t) {
                pictureSum = t.pictureSum;
                soundSum = t.soundSum;
                pictures = t.pictures;
                sounds = t.sounds;
                t.pictureSum = t.soundSum = 0;
                t.pictures = t.sounds = 0;
            }
            if (pictures == 0 && sounds == 0) continue;
            PlayerInfo info = mc.getConnection() != null ? mc.getConnection().getPlayerInfo(e.getKey()) : null;
            String name = info != null ? info.getProfile().getName() : "?";
            long picture = pictures > 0 ? pictureSum / pictures : 0, sound = sounds > 0 ? soundSum / sounds : 0;
            boolean onePc = (pictures == 0 || plausible(picture)) && (sounds == 0 || plausible(sound));
            StringBuilder line = new StringBuilder("Shared screen from ").append(name).append(", timing: ");
            if (onePc) {
                if (pictures > 0) line.append("pictures show ").append(ms(picture)).append(" ms after ").append(name).append("'s screen had them");
                if (pictures > 0 && sounds > 0) line.append(", ");
                if (sounds > 0) line.append("sound is heard ").append(ms(sound)).append(" ms after it played there (plus the sound card's own delay)");
                if (pictures > 0 && sounds > 0) line.append("; ");
            }
            if (pictures > 0 && sounds > 0) {
                long behind = sound - picture;
                line.append(behind >= 0 ? "the sound is " + ms(behind) + " ms behind the picture" : "the sound is " + ms(-behind) + " ms ahead of the picture");
            } else if (!onePc) {
                line.append("only ").append(pictures > 0 ? "pictures" : "sound").append(" came, and the clocks differ (two PCs), so nothing to compare");
            }
            if (!onePc && pictures > 0 && sounds > 0) line.append(" (two PCs: only this difference is known)");
            DesktopScreens.LOG.info(line.toString());
        }
    }

    private static boolean plausible(long delay) {
        return delay >= 0 && delay < PLAUSIBLE_NANOS;
    }

    private static long ms(long nanos) {
        return Math.round(nanos / 1e6);
    }
}
