package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;

import java.util.Locale;

/**
 * Frame timing of the desktop view or of the screens in the world, logged once per stretch of doing one thing
 * (watching a monitor, controlling it, the settings preview, showing it in the world), so smoothness problems can be
 * read from the log.
 */
final class FrameStats {
    private static final double MIN_SECONDS = 2; // shorter stretches say nothing useful
    /**
     * Nothing drawn for this long ends a stretch: the view or the screens weren't in sight (the settings preview while
     * using a screen, say). Counted in, such pauses showed as a few fps and a slowest frame of many seconds.
     */
    private static final long GAP_NANOS = 1_000_000_000L;
    /** The capture method while the real screen shows through the game window (click-through). */
    static final String SEE_THROUGH = "see-through";

    /** What is being timed, first in each log line, like "Desktop view". */
    private final String what;
    private String activity, captureMethod;
    private long since, lastFrame, frames, slowest, drawNanos, uploads, uploadNanos;
    private double captureMillis;

    FrameStats(String what) {
        this.what = what;
    }

    /**
     * Call at the start of each frame. A new activity or capture method, or a pause in drawing, logs the previous
     * stretch and starts over.
     */
    void frame(String activity, String captureMethod, double captureMillis) {
        long now = System.nanoTime();
        if (!activity.equals(this.activity) || !captureMethod.equals(this.captureMethod) || frames > 0 && now - lastFrame > GAP_NANOS) {
            flush();
            this.activity = activity;
            this.captureMethod = captureMethod;
        }
        if (frames == 0) since = now; // a stretch starts with its first frame, not with the last one logged
        else slowest = Math.max(slowest, now - lastFrame);
        lastFrame = now;
        frames++;
        this.captureMillis = captureMillis;
    }

    /** Time spent drawing the desktop this frame, uploads included. */
    void drew(long nanos) {
        drawNanos += nanos;
    }

    void uploaded(long nanos) {
        uploads++;
        uploadNanos += nanos;
    }

    /** Logs the current stretch (its first frame to its last), if it was long enough, and starts over. */
    void flush() {
        double seconds = (lastFrame - since) / 1e9;
        double fps = (frames - 1) / seconds;
        if (activity != null && frames > 1 && seconds >= MIN_SECONDS && SEE_THROUGH.equals(captureMethod)) {
            DesktopScreens.LOG.info("{}, {} for {} s: the game drew {} fps (slowest frame {} ms). The real screen shows"
                            + " through the game window, nothing captured; drawing took {} ms per frame.",
                    what, activity, format(seconds, 0), format(fps, 1), format(slowest / 1e6, 0), format(drawNanos / 1e6 / frames, 2));
        } else if (activity != null && frames > 1 && seconds >= MIN_SECONDS) {
            DesktopScreens.LOG.info("{}, {} for {} s: the game drew {} fps (slowest frame {} ms). Drawing the desktop took {} ms"
                            + " per frame, uploads {} ms ({} new pictures per second). Capture ({}) took {} ms per frame.",
                    what, activity, format(seconds, 0), format(fps, 1), format(slowest / 1e6, 0),
                    format(drawNanos / 1e6 / frames, 2), format(uploads == 0 ? 0 : uploadNanos / 1e6 / uploads, 2),
                    format(uploads / seconds, 0), captureMethod, format(captureMillis, 1));
        }
        lastFrame = 0;
        frames = slowest = drawNanos = uploads = uploadNanos = 0;
    }

    private static String format(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }
}
