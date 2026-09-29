package com.desktopscreens.core;

import com.desktopscreens.core.Win32.CURSORINFO;
import com.desktopscreens.core.Win32.G32;
import com.desktopscreens.core.Win32.ICONINFO;
import com.desktopscreens.core.Win32.U32;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.PointerByReference;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Captures one monitor on a background thread, including the real mouse cursor. It keeps two BGRA buffers and
 * swaps them (one being written, one ready), and the render thread reads the ready one straight from native
 * memory, so no pixel ever passes through Java.
 *
 * <p>It uses DXGI Desktop Duplication where it can: a new frame as soon as the screen changes, up to the
 * monitor's refresh rate, and none while nothing changes. GDI is the fallback (about 60 fps at most), also while
 * Desktop Duplication is briefly unavailable, like during a resolution change, and while it isn't allowed
 * (see {@link #setDuplicationAllowed}).
 */
public final class DesktopCapture implements AutoCloseable, PictureFeed {
    public interface FrameSink {
        /** {@code bgraAddress} points at {@code width * height} top-down BGRA pixels. Only valid during the call. */
        void accept(long bgraAddress, int width, int height);
    }

    /** Where to report when Desktop Duplication starts or stops, and why. Called on the capture thread. */
    public interface Log {
        void log(String message);
    }

    private static final long GDI_FRAME_NANOS = 1000000000L / 60;
    /** How often Graphics Capture is asked for a new picture: up to the monitor's refresh rate, at no cost in between. */
    private static final int GRAPHICS_CAPTURE_POLL_MILLIS = 3;
    private static final long MOUSE_FRAME_NANOS = 1000000000L / 250;
    /** How long one wait for a change may take, so {@link #close} is noticed soon. */
    private static final int WAIT_MILLIS = 50, MOUSE_WAIT_MILLIS = 4;
    private static final long FIRST_RETRY_NANOS = 250000000L, LAST_RETRY_NANOS = 8000000000L;
    /** A duplication that ran this long was working; if it stops after that, the next try comes soon again. */
    private static final long WORKED_NANOS = 2000000000L;

    /** The newest capture thread. The next one waits for it, since only one duplication per monitor can exist. */
    private static Thread newest; // guarded by DesktopCapture.class

    private final Monitor monitor;
    private final boolean tryDuplication;
    private final Log log;
    private Thread previous; // capture thread only
    private final Object lock = new Object();
    private final Map<Long, int[]> hotspots = new HashMap<Long, int[]>(); // capture thread only
    private Frame front; // guarded by lock
    private Frame back;  // capture thread only
    private boolean fresh; // guarded by lock
    private long published; // guarded by lock: frames published so far, for readers besides the render thread
    private long frontTime; // guarded by lock: when the newest frame was taken (System.nanoTime)
    private boolean mouseMovePending; // capture thread only
    private long lastPublished;       // capture thread only
    private volatile boolean running = true, drawCursor = true, duplicationAllowed = true, duplicating, paused, protectedContent;
    /**
     * While the game covers the monitor fullscreen: Windows Graphics Capture of the monitor instead of GDI, which took
     * 16-20 ms a picture there and cost the game a third of its frames (45 instead of 90-110 fps, 2026-09-26).
     * Capture thread only, except the flag.
     */
    private GraphicsCapture graphicsCapture;
    private volatile boolean graphicsCapturing, graphicsCaptureAllowed = true;
    private boolean graphicsCaptureFailed;
    /** A picture of another size than the monitor (mid resolution change) goes here, and is dropped. */
    private Memory oddBuffer;
    private volatile double averageMillis;
    private volatile String error;

    /** {@code fast}: use Desktop Duplication when possible. Off means GDI only. */
    public DesktopCapture(Monitor monitor, boolean fast, Log log) {
        this.monitor = monitor;
        this.tryDuplication = fast;
        this.log = log;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                captureLoop();
            }
        }, "Desktop Screens capture");
        thread.setDaemon(true);
        synchronized (DesktopCapture.class) {
            previous = newest; // read by the new thread only, after start() (which makes it visible there)
            newest = thread;
        }
        thread.start();
    }

    public Monitor monitor() {
        return monitor;
    }

    /** Hands the newest frame to {@code sink} if it hasn't seen it yet. Returns whether it did. */
    public boolean consumeLatest(FrameSink sink) {
        synchronized (lock) {
            if (!fresh || front == null) return false;
            fresh = false;
            sink.accept(front.address(), front.width, front.height);
            return true;
        }
    }

    /**
     * For a second reader on another thread (sharing the screen with other players): hands the newest frame to
     * {@code sink} if it's newer than frame number {@code after}, without taking it from {@link #consumeLatest}.
     * Returns the frame's number, or {@code after} if there's no newer one. The capture waits while {@code sink} runs,
     * so it should only copy what it needs.
     */
    public long readLatest(long after, FrameSink sink) {
        synchronized (lock) {
            if (front == null || published <= after) return after;
            sink.accept(front.address(), front.width, front.height);
            return published;
        }
    }

    /** Time to capture one frame, not counting waiting for the screen to change. */
    public double averageMillis() {
        return averageMillis;
    }

    /** True while frames come from DXGI Desktop Duplication, false while from GDI or Graphics Capture. */
    public boolean duplicating() {
        return duplicating;
    }

    /** How the frames come right now, for the log: "DXGI", "Graphics Capture" or "GDI". */
    public String method() {
        return duplicating ? "DXGI" : graphicsCapturing ? "Graphics Capture" : "GDI";
    }

    /** The last capture error, or null while capturing works. */
    public String error() {
        return error;
    }

    /** Leave the cursor out of the picture when the real one is already visible right where it would be drawn. */
    public void setDrawCursor(boolean draw) {
        drawCursor = draw;
    }

    /**
     * Turn this off while the fullscreen game shows its own cursor (a menu is open). While Windows Graphics Capture
     * takes this monitor under a fullscreen game, the game's cursor isn't drawn at all (F11 only;
     * 2026-09-26), so GDI captures meanwhile; while playing, the cursor is hidden anyway. Cheap to call every frame.
     */
    public void setGraphicsCaptureAllowed(boolean allowed) {
        graphicsCaptureAllowed = allowed;
    }

    /**
     * Turn this off while a fullscreen window (the game) covers the monitor exactly. Windows then shows that window
     * straight to the screen whenever it can, and every switch interrupts Desktop Duplication, which never settles.
     * Graphics Capture (or GDI) captures meanwhile. Cheap to call every frame.
     */
    public void setDuplicationAllowed(boolean allowed) {
        duplicationAllowed = allowed;
    }

    /**
     * Stops capturing for a while, e.g. while the game shows the real screen through itself (see {@link ClickThrough}).
     * The first frame after resuming comes right away.
     */
    public void setPaused(boolean pause) {
        paused = pause;
    }

    /**
     * True while Windows blacks out protected video (DRM) in the picture, as it does in every screen capture. Only
     * Desktop Duplication reports it; with GDI it's always false.
     */
    public boolean protectedContentHidden() {
        return protectedContent;
    }

    @Override
    public void close() {
        running = false;
    }

    private void captureLoop() {
        Duplication duplication = null;
        long openedAt = 0, retryAt = System.nanoTime(), retryDelay = FIRST_RETRY_NANOS;
        boolean wasAllowed = true;
        try {
            // GDI objects are created on this thread, the only one that draws into them.
            back = new Frame(monitor.width, monitor.height);
            Frame other = new Frame(monitor.width, monitor.height);
            synchronized (lock) {
                front = other;
            }
            if (tryDuplication) {
                grabWithGdi(); // a picture right away: opening Desktop Duplication can take ~250 ms
                // The previous capture (another monitor, or fast capture switched off and on) may still be closing its
                // duplication; it only waits up to 50 ms for the screen to change before it notices.
                if (previous != null) previous.join(1000);
                previous = null;
            } else {
                log.log("Capturing " + monitor.label() + " with GDI: fast capture is off");
            }
            boolean wasPaused = false;
            while (running) {
                if (paused) {
                    if (!wasPaused) {
                        wasPaused = true;
                        if (duplication != null) {
                            duplication.close();
                            duplication = null;
                            duplicating = false;
                        }
                        stopGraphicsCapture();
                        protectedContent = false;
                        log.log("Paused on " + monitor.label() + ": the game shows the real screen through itself");
                    }
                    Thread.sleep(20);
                    continue;
                }
                if (wasPaused) {
                    wasPaused = false;
                    retryAt = System.nanoTime(); // Desktop Duplication again right away
                    retryDelay = FIRST_RETRY_NANOS;
                    grabWithGdi(); // the last picture is from before the pause
                }
                long now = System.nanoTime();
                boolean allowed = duplicationAllowed;
                if (allowed != wasAllowed && tryDuplication) {
                    wasAllowed = allowed;
                    if (allowed) {
                        retryAt = now; // try right away
                        retryDelay = FIRST_RETRY_NANOS;
                    } else {
                        if (duplication != null) {
                            duplication.close();
                            duplication = null;
                            duplicating = false;
                            protectedContent = false;
                        }
                        if (graphicsCaptureFailed) log.log("Capturing " + monitor.label() + " with GDI: the game covers it fullscreen, which keeps interrupting Desktop Duplication");
                    }
                }
                // Under the fullscreen game: Graphics Capture, but GDI while the game shows a menu (see
                // setGraphicsCaptureAllowed). Also started again after a pause.
                boolean menu = !graphicsCaptureAllowed;
                boolean wantGraphics = !allowed && tryDuplication && !menu && !graphicsCaptureFailed;
                if (wantGraphics && graphicsCapture == null) {
                    startGraphicsCapture();
                } else if (!wantGraphics && graphicsCapture != null) {
                    stopGraphicsCapture();
                    if (!allowed) log.log("Capturing " + monitor.label() + " with GDI while the game shows a menu: under Graphics Capture of this monitor its cursor isn't drawn");
                }
                if (duplication == null && tryDuplication && allowed && now - retryAt >= 0) {
                    try {
                        duplication = Duplication.open(monitor);
                        openedAt = now;
                        duplicating = true;
                        mouseMovePending = false; // a new duplication has no picture to draw the cursor on yet
                        error = null; // the screen can be read again, even if nothing on it changes for a while
                        log.log("Capturing " + monitor.label() + " with DXGI Desktop Duplication");
                    } catch (RuntimeException e) {
                        log.log("Desktop Duplication couldn't start on " + monitor.label() + " (" + e.getMessage()
                                + "); GDI until the next try in " + seconds(retryDelay) + " s");
                        retryAt = now + retryDelay;
                        retryDelay = Math.min(retryDelay * 2, LAST_RETRY_NANOS);
                    }
                }
                if (duplication != null) {
                    try {
                        duplicate(duplication);
                        continue;
                    } catch (RuntimeException e) {
                        // Lost, most likely: a resolution change, a UAC prompt, the lock screen. GDI until it's back.
                        duplication.close();
                        duplication = null;
                        duplicating = false;
                        protectedContent = false;
                        // Soon again if it had been working; if it keeps stopping right after starting, less and less often.
                        if (now - openedAt >= WORKED_NANOS) retryDelay = FIRST_RETRY_NANOS;
                        log.log("Desktop Duplication stopped after " + seconds(now - openedAt) + " s (" + e.getMessage()
                                + "); GDI until the next try in " + seconds(retryDelay) + " s");
                        retryAt = now + retryDelay;
                        retryDelay = Math.min(retryDelay * 2, LAST_RETRY_NANOS);
                    }
                }
                if (graphicsCapture != null) {
                    try {
                        if (!grabWithGraphicsCapture()) Thread.sleep(GRAPHICS_CAPTURE_POLL_MILLIS);
                        continue;
                    } catch (RuntimeException e) {
                        log.log("Windows Graphics Capture of " + monitor.label() + " stopped (" + e.getMessage() + "); GDI from now on");
                        stopGraphicsCapture();
                        graphicsCaptureFailed = true;
                    }
                }
                long took = grabWithGdi();
                recordTime(took);
                long sleepMillis = (GDI_FRAME_NANOS - took) / 1000000L;
                if (sleepMillis > 0) Thread.sleep(sleepMillis);
            }
        } catch (InterruptedException ignored) {
            // shutting down
        } catch (RuntimeException e) {
            error = String.valueOf(e.getMessage());
        } finally {
            if (duplication != null) duplication.close();
            stopGraphicsCapture();
            synchronized (lock) {
                fresh = false;
                if (front != null) front.free();
                if (back != null) back.free();
                front = null;
                back = null;
            }
        }
    }

    /** Under the fullscreen game: Graphics Capture of the monitor if it starts, else GDI (logged either way). */
    private void startGraphicsCapture() {
        stopGraphicsCapture();
        if (!graphicsCaptureFailed) {
            try {
                graphicsCapture = GraphicsCapture.ofMonitor(monitor);
                graphicsCapturing = true;
                log.log("Capturing " + monitor.label() + " with Windows Graphics Capture: the game covers it fullscreen, which keeps interrupting Desktop Duplication");
                return;
            } catch (RuntimeException e) {
                graphicsCaptureFailed = true;
                log.log("Windows Graphics Capture couldn't start on " + monitor.label() + " (" + e.getMessage() + ")");
            }
        }
        log.log("Capturing " + monitor.label() + " with GDI: the game covers it fullscreen, which keeps interrupting Desktop Duplication");
    }

    private void stopGraphicsCapture() {
        if (graphicsCapture == null) return;
        graphicsCapture.close();
        graphicsCapture = null;
        graphicsCapturing = false;
    }

    /** Publishes the next picture from Graphics Capture if there is one. False if there wasn't. */
    private boolean grabWithGraphicsCapture() {
        long start = System.nanoTime();
        int[] got = graphicsCapture.next(new GraphicsCapture.Target() {
            @Override
            public Pointer bufferFor(int width, int height) {
                if (width == monitor.width && height == monitor.height) return back.bits;
                long bytes = (long) width * height * 4;
                if (oddBuffer == null || oddBuffer.size() < bytes) oddBuffer = new Memory(bytes);
                return oddBuffer;
            }
        });
        if (got == null) return false;
        if (got[0] != monitor.width || got[1] != monitor.height) return true; // not this monitor's size (yet): skip it
        if (drawCursor) back.drawCursor(monitor, hotspots);
        publish(start);
        error = null;
        recordTime(System.nanoTime() - start);
        return true;
    }

    /** Captures and publishes one frame with GDI. Returns how long that took. */
    private long grabWithGdi() {
        long start = System.nanoTime();
        try {
            back.grab(monitor);
            if (drawCursor) back.drawCursor(monitor, hotspots);
            publish(start);
            error = null;
        } catch (RuntimeException e) {
            error = String.valueOf(e.getMessage()); // e.g. the lock screen or a UAC prompt is up; keep trying
        }
        return System.nanoTime() - start;
    }

    private static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1e9);
    }

    /**
     * Waits for the screen to change and publishes a frame if it did. Never sleeps: Windows' sleep can take up
     * to 15.6 ms, which would cap the picture at 60 fps again.
     */
    private void duplicate(Duplication duplication) {
        Duplication.Change change = duplication.next(mouseMovePending ? MOUSE_WAIT_MILLIS : WAIT_MILLIS);
        boolean cursor = drawCursor;
        if (change == Duplication.Change.POINTER && cursor || change == Duplication.Change.NOTHING && mouseMovePending) {
            // New pictures come at most at the monitor's refresh rate, but mouse moves may come faster. Publish those
            // at most every MOUSE_FRAME_NANOS, and a skipped one after a short wait, so the cursor ends up where it stopped.
            if (System.nanoTime() - lastPublished < MOUSE_FRAME_NANOS) {
                mouseMovePending = true;
                return;
            }
        } else if (change != Duplication.Change.IMAGE) {
            return;
        }
        mouseMovePending = false;
        protectedContent = duplication.protectedContentHidden();
        long start = System.nanoTime();
        duplication.copyTo(back.bits);
        if (cursor) back.drawCursor(monitor, hotspots);
        publish(start);
        error = null;
        lastPublished = System.nanoTime();
        recordTime(lastPublished - start);
    }

    private void publish(long takenAt) {
        synchronized (lock) {
            Frame done = back;
            back = front;
            front = done;
            fresh = true;
            published++;
            frontTime = takenAt;
        }
    }

    /**
     * When the newest frame was taken ({@link System#nanoTime}), for measuring how late shared pictures arrive. Call
     * it from inside {@link #readLatest}'s sink to get the time of the frame it hands over.
     */
    public long frameTime() {
        synchronized (lock) {
            return frontTime;
        }
    }

    private void recordTime(long nanos) {
        averageMillis = averageMillis == 0 ? nanos / 1e6 : averageMillis * 0.95 + nanos / 1e6 * 0.05;
    }

    /**
     * A 32-bit top-down DIB section: GDI draws into it (the screen, or only the cursor over a duplicated
     * frame), and its pixels sit in memory we can hand to OpenGL.
     */
    static final class Frame {
        final int width, height;
        private final HDC dc;
        private final HBITMAP bitmap;
        final Pointer bits;

        Frame(int width, int height) {
            this.width = width;
            this.height = height;
            WinGDI.BITMAPINFO bmi = new WinGDI.BITMAPINFO();
            bmi.bmiHeader.biWidth = width;
            bmi.bmiHeader.biHeight = -height; // negative height = rows stored top to bottom
            bmi.bmiHeader.biPlanes = 1;
            bmi.bmiHeader.biBitCount = 32;
            bmi.bmiHeader.biCompression = 0; // BI_RGB
            PointerByReference ppv = new PointerByReference();
            dc = G32.I.CreateCompatibleDC(null);
            bitmap = G32.I.CreateDIBSection(dc, bmi, 0 /* DIB_RGB_COLORS */, ppv, null, 0);
            if (bitmap == null) {
                G32.I.DeleteDC(dc);
                throw new IllegalStateException("CreateDIBSection failed for " + width + "x" + height);
            }
            G32.I.SelectObject(dc, bitmap);
            bits = ppv.getValue();
        }

        long address() {
            return Pointer.nativeValue(bits);
        }

        /** Its memory device context, to draw into it with GDI (the window's own picture, {@link WindowCapture}). */
        HDC dc() {
            return dc;
        }

        void grab(Monitor m) {
            HDC screen = User32.INSTANCE.GetDC(null);
            boolean ok;
            try {
                ok = G32.I.BitBlt(dc, 0, 0, width, height, screen, m.x, m.y, Win32.SRCCOPY);
            } finally {
                User32.INSTANCE.ReleaseDC(null, screen);
            }
            if (!ok) throw new IllegalStateException("Couldn't read the screen (BitBlt failed)");
            G32.I.GdiFlush(); // GDI may queue drawing; the pixels must be there before another thread reads them
        }

        /**
         * Screen capture never includes the mouse cursor, so draw the real one in whatever shape Windows shows
         * right now (resize arrows, text cursor, hand...). DrawIconEx also gets the inverting parts right.
         */
        void drawCursor(Monitor m, Map<Long, int[]> hotspots) {
            drawCursor(m.x, m.y, hotspots);
        }

        /** The same for a picture whose top-left pixel is at ({@code originX}, {@code originY}) on the desktop. */
        void drawCursor(int originX, int originY, Map<Long, int[]> hotspots) {
            CURSORINFO ci = new CURSORINFO();
            if (!U32.I.GetCursorInfo(ci) || (ci.flags & Win32.CURSOR_SHOWING) == 0 || ci.hCursor == null) return;
            int x = ci.ptScreenPos.x, y = ci.ptScreenPos.y;
            if (x < originX || y < originY || x >= originX + width || y >= originY + height) return;
            if (hotspots.size() > 256) hotspots.clear(); // apps can create cursors on the fly; don't grow forever
            Long key = Pointer.nativeValue(ci.hCursor);
            int[] hot = hotspots.get(key);
            if (hot == null) {
                hot = hotspot(ci.hCursor);
                hotspots.put(key, hot);
            }
            U32.I.DrawIconEx(dc, x - originX - hot[0], y - originY - hot[1], ci.hCursor, 0, 0, 0, null, Win32.DI_NORMAL);
            G32.I.GdiFlush();
        }

        /** The cursor's click point inside its image. */
        private static int[] hotspot(Pointer cursor) {
            ICONINFO info = new ICONINFO();
            if (!U32.I.GetIconInfo(cursor, info)) return new int[2];
            // GetIconInfo gives us copies of the cursor's bitmaps, which we must free.
            if (info.hbmMask != null) G32.I.DeleteObject(new HANDLE(info.hbmMask));
            if (info.hbmColor != null) G32.I.DeleteObject(new HANDLE(info.hbmColor));
            return new int[] {info.xHotspot, info.yHotspot};
        }

        void free() {
            G32.I.DeleteDC(dc);
            G32.I.DeleteObject(bitmap);
        }
    }
}
