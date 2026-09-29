package com.desktopscreens.core;

/**
 * Pictures a second reader can take on its own thread without taking them from the renderer: a monitor's capture
 * ({@link DesktopCapture}) or one window's ({@link WindowCapture}). Sharing a screen reads them this way.
 */
public interface PictureFeed {
    /**
     * Hands the newest picture to {@code sink} if it's newer than picture number {@code after}. Returns its number, or
     * {@code after} if there's no newer one. The capture waits while {@code sink} runs, so it should only copy.
     */
    long readLatest(long after, DesktopCapture.FrameSink sink);

    /** When the newest picture was taken ({@link System#nanoTime}); call it from inside {@link #readLatest}'s sink. */
    long frameTime();
}
