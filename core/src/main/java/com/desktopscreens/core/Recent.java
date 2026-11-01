package com.desktopscreens.core;

/**
 * A value worked out lately, kept for a while so it isn't worked out again for every event: the window's menus for the
 * mouse lock ({@link InputSession}), which walked every window on the PC for each of Windows' events, on the thread
 * that also runs the PC's keyboard hook (the final review, 2026-11-01). Not thread-safe.
 */
final class Recent<T> {
    private final long ageMillis;
    private T value;
    private long since;

    /** Keeps each value for {@code ageMillis}. */
    Recent(long ageMillis) {
        this.ageMillis = ageMillis;
    }

    /** The value put in less than its age before {@code nowMillis} (any steady clock), or null. */
    T get(long nowMillis) {
        return value != null && nowMillis - since < ageMillis ? value : null;
    }

    void put(T value, long nowMillis) {
        this.value = value;
        since = nowMillis;
    }

    /** The next {@link #get} is null: what it was about changed. */
    void forget() {
        value = null;
    }
}
