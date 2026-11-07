package com.desktopscreens.core;

/**
 * Decides which of a stream's numbers, reported every 10 s, go in the log everyone reads. The first report does, so a
 * bug report still shows how the stream went. After that only trouble does, at most once a minute, or a long shared
 * video would add hundreds of lines to the console. Most games keep no debug log, so a report that isn't shown here
 * may be read by nobody.
 */
public final class RepeatingReport {
    private static final long TROUBLE_EVERY_NANOS = 60000000000L;

    private boolean reported, troubleShown;
    private long troubleAt;

    /** @param now {@link System#nanoTime()}, which can be any number */
    public boolean show(long now, boolean trouble) {
        boolean show = !reported;
        reported = true;
        if (trouble && (!troubleShown || now - troubleAt >= TROUBLE_EVERY_NANOS)) {
            troubleShown = true;
            troubleAt = now;
            show = true;
        }
        return show;
    }
}
