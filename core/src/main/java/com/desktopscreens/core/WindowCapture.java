package com.desktopscreens.core;

import com.desktopscreens.core.Win32.CURSORINFO;
import com.desktopscreens.core.Win32.U32;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Captures one window by itself ({@link GraphicsCapture}) on a background thread, so it comes through while other
 * windows, or the game, cover it: a monitor's picture cut to the window's frame shows whatever covers it, which broke
 * a window per screen (2026-09-25). Its menus and tooltips are windows of their own and aren't in the picture; a
 * minimized window sends nothing (the last picture stays); protected video is black, as in any capture.
 *
 * <p>The real cursor is drawn in, as for monitors ({@link DesktopCapture}): Graphics Capture's own only moved with new
 * pictures, and none showed while using a screen (2026-09-26). So the last picture is kept clean, and a mouse
 * move over the window publishes it again with the cursor at its new place. Two buffers swapped as in
 * {@link DesktopCapture}, so the render thread reads the newest picture straight from native memory.
 */
public final class WindowCapture implements AutoCloseable, PictureFeed {
    /** How often to look for a new picture and a moved cursor: often enough for 240 Hz, rarely enough to cost nothing. */
    private static final int POLL_MILLIS = 3;
    private static final long ALIVE_CHECK_NANOS = 500000000L, MOUSE_FRAME_NANOS = 1000000000L / 250;
    private static final int DWMWA_EXTENDED_FRAME_BOUNDS = 9;
    /** While a menu is open, PrintWindow at most this often: 30 pictures a second. */
    private static final long PRINT_NANOS = 1000000000L / 30;

    private final long window;
    private final String label;
    private final DesktopCapture.Log log;
    private final Object lock = new Object();
    private final Map<Long, int[]> hotspots = new HashMap<Long, int[]>(); // capture thread only
    private DesktopCapture.Frame front, back; // front guarded by lock; back capture thread only
    /** The newest picture without the cursor, to draw it again elsewhere. Capture thread only. */
    private Memory clean;
    private int cleanWidth, cleanHeight;
    private boolean fresh;   // guarded by lock
    private long published;  // guarded by lock
    private long frontTime;  // guarded by lock
    private volatile boolean running = true, gone;
    private volatile boolean graphicsCaptureAllowed;
    /** Pictures Graphics Capture delivered (not counting the cursor drawn anew onto the last one). */
    private volatile long captured;
    /** False once PrintWindow gave no picture of this window (see {@link #print}). */
    private volatile boolean printWorks = true;
    // Capture thread only: PrintWindow's picture of the whole window, and how it went while the menu was open.
    private DesktopCapture.Frame printFrame;
    private int printMisses, printCount;
    private long lastPrinted, printFrom, printNanos;
    private volatile double averageMillis;
    private volatile String error;
    // Capture thread only: where the cursor was drawn last, to notice it moving.
    private int cursorX = Integer.MIN_VALUE, cursorY;
    private long cursorShape, lastPublished;
    private boolean cursorShown, cursorDrawn;

    /**
     * Starts capturing {@code window} (its handle); {@code label} names it in the log, never its title. With
     * {@code allowed} false it waits until {@link #setGraphicsCaptureAllowed} allows it.
     */
    public WindowCapture(long window, String label, boolean allowed, DesktopCapture.Log log) {
        this.window = window;
        this.label = label;
        this.log = log;
        graphicsCaptureAllowed = allowed;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                captureLoop();
            }
        }, "Desktop Screens window capture");
        thread.setDaemon(true);
        thread.start();
    }

    public long window() {
        return window;
    }

    /** Hands the newest picture to {@code sink} if it hasn't seen it yet. Returns whether it did. */
    public boolean consumeLatest(DesktopCapture.FrameSink sink) {
        synchronized (lock) {
            if (!fresh || front == null) return false;
            fresh = false;
            sink.accept(front.address(), front.width, front.height);
            return true;
        }
    }

    /** For a second reader on another thread, as {@link DesktopCapture#readLatest}. */
    public long readLatest(long after, DesktopCapture.FrameSink sink) {
        synchronized (lock) {
            if (front == null || published <= after) return after;
            sink.accept(front.address(), front.width, front.height);
            return published;
        }
    }

    /** When the newest picture was taken ({@link System#nanoTime}); call it from inside {@link #readLatest}'s sink. */
    public long frameTime() {
        synchronized (lock) {
            return frontTime;
        }
    }

    /** Time to copy one picture, not counting waiting for the window to change. */
    public double averageMillis() {
        return averageMillis;
    }

    /** Why it doesn't work (Graphics Capture unavailable, the window can't be captured...), or null while it does. */
    public String error() {
        return error;
    }

    /** True once the window was closed. */
    public boolean windowGone() {
        return gone;
    }

    /** How many pictures Graphics Capture delivered so far; goes up only with new content, not a moved cursor. */
    public long capturedPictures() {
        return captured;
    }

    /**
     * Whether the window's own picture goes on while a menu keeps Graphics Capture off ({@link #print}); false once
     * that turned out not to work for this window, and then the caller shows something else meanwhile.
     */
    public boolean picturesInMenus() {
        return printWorks;
    }

    /**
     * Turn this off while the fullscreen game shows its own cursor (a menu is open): while any Graphics Capture runs,
     * Windows sometimes doesn't draw the fullscreen game's cursor at all, and it came back only once the capture
     * stopped (2026-09-26; first seen with the game's own monitor, then with a window on the other one).
     * The capture stops meanwhile and its last picture stays; the cursor is still drawn onto it. Cheap to call often.
     */
    public void setGraphicsCaptureAllowed(boolean allowed) {
        graphicsCaptureAllowed = allowed;
    }

    @Override
    public void close() {
        running = false;
    }

    private void captureLoop() {
        GraphicsCapture capture = null;
        boolean started = false;
        try {
            long aliveCheckedAt = System.nanoTime();
            GraphicsCapture.Target target = new GraphicsCapture.Target() {
                @Override
                public Pointer bufferFor(int width, int height) {
                    long bytes = (long) width * height * 4;
                    if (clean == null || clean.size() < bytes) clean = new Memory(bytes);
                    cleanWidth = width;
                    cleanHeight = height;
                    return clean;
                }
            };
            while (running) {
                long now = System.nanoTime();
                if (now - aliveCheckedAt > ALIVE_CHECK_NANOS) {
                    aliveCheckedAt = now;
                    if (!User32.INSTANCE.IsWindow(Win32.hwnd(window))) {
                        gone = true;
                        log.log("Stopped capturing " + label + ": the window was closed");
                        break;
                    }
                }
                boolean allowed = graphicsCaptureAllowed;
                if (allowed && capture == null) {
                    capture = GraphicsCapture.ofWindow(window);
                    log.log((started ? "Capturing " + label + " again" : "Capturing " + label + " with Windows Graphics Capture")
                            + (capture.borderless ? "" : " (Windows draws a yellow frame around it)"));
                    started = true;
                    logPrinting(now);
                } else if (!allowed && capture != null) {
                    capture.close();
                    capture = null;
                    log.log("Paused Graphics Capture of " + label + " while the fullscreen game shows a menu (its cursor isn't always drawn meanwhile)"
                            + (printWorks ? "; PrintWindow takes its pictures meanwhile" : ""));
                    printFrom = now;
                }
                long start = System.nanoTime();
                if (capture != null && capture.next(target) != null) {
                    compose(start);
                    captured++; // after it's up for the taking
                    averageMillis = capture.averageMillis();
                    error = null;
                } else if (capture == null && !allowed && printWorks && now - lastPrinted >= PRINT_NANOS && print()) {
                    compose(start);
                } else if (clean != null && now - lastPublished >= MOUSE_FRAME_NANOS && cursorNeedsRedraw()) {
                    compose(start);
                } else {
                    Thread.sleep(POLL_MILLIS);
                }
            }
        } catch (InterruptedException ignored) {
            // shutting down
        } catch (RuntimeException e) {
            error = String.valueOf(e.getMessage());
            log.log("Windows Graphics Capture of " + label + " failed: " + error);
        } finally {
            if (capture != null) capture.close();
            if (printFrame != null) printFrame.free();
            synchronized (lock) {
                fresh = false;
                if (front != null) front.free();
                if (back != null) back.free();
                front = null;
                back = null;
            }
        }
    }

    /**
     * While a menu keeps Graphics Capture off (see {@link #setGraphicsCaptureAllowed}): the window's own picture from
     * PrintWindow into {@link #clean}, also what the graphics card draws (PW_RENDERFULLCONTENT, Windows 8.1 and up), and
     * covered or not, cut to the window's visible frame, which is what Graphics Capture takes; so a video in
     * picture-in-picture goes on in the inventory, window and all (2026-09-26). At most {@link #PRINT_NANOS}
     * apart. True if there's a new picture. A failed or all-black picture three times running means it doesn't work
     * for this window ({@link #picturesInMenus}). A minimized window keeps its last picture.
     */
    private boolean print() {
        lastPrinted = System.nanoTime();
        HWND hwnd = Win32.hwnd(window);
        if (U32.I.IsIconic(hwnd)) return false;
        RECT outer = new RECT(), visible = new RECT();
        if (!User32.INSTANCE.GetWindowRect(hwnd, outer)
                || Win32.Dwm.I.DwmGetWindowAttribute(hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, visible, visible.size()) != 0) {
            return false;
        }
        int ow = outer.right - outer.left, oh = outer.bottom - outer.top;
        // PrintWindow draws the whole window, invisible resize borders included; Graphics Capture's pictures don't have them.
        int x0 = Math.max(0, visible.left - outer.left), y0 = Math.max(0, visible.top - outer.top);
        int w = Math.min(ow - x0, visible.right - visible.left), h = Math.min(oh - y0, visible.bottom - visible.top);
        if (ow <= 0 || oh <= 0 || w <= 0 || h <= 0) return false;
        if (printFrame == null || printFrame.width != ow || printFrame.height != oh) {
            if (printFrame != null) printFrame.free();
            printFrame = new DesktopCapture.Frame(ow, oh);
        }
        long start = System.nanoTime();
        boolean ok = U32.I.PrintWindow(hwnd, printFrame.dc(), Win32.PW_RENDERFULLCONTENT);
        Win32.G32.I.GdiFlush();
        if (!ok || black(printFrame.bits, ow, x0, y0, w, h)) {
            if (++printMisses >= 3) {
                printWorks = false;
                log.log("PrintWindow gives no picture of " + label + ": its part of the monitor shows while a menu is open");
            }
            return false;
        }
        printMisses = 0;
        long row = w * 4L, bytes = row * h;
        if (clean == null || clean.size() < bytes) clean = new Memory(bytes);
        for (int r = 0; r < h; r++) {
            clean.getByteBuffer(r * row, row).put(printFrame.bits.getByteBuffer(((long) (y0 + r) * ow + x0) * 4, row));
        }
        cleanWidth = w;
        cleanHeight = h;
        printCount++;
        printNanos += System.nanoTime() - start;
        return true;
    }

    /** Whether the part of a picture ({@code stride} pixels a row) is all black, from 64 pixels spread over it. */
    private static boolean black(Pointer pixels, int stride, int x0, int y0, int w, int h) {
        for (int j = 1; j <= 8; j++) {
            for (int i = 1; i <= 8; i++) {
                long at = ((long) (y0 + h * j / 9) * stride + x0 + w * i / 9) * 4;
                if ((pixels.getInt(at) & 0xFFFFFF) != 0) return false;
            }
        }
        return true;
    }

    /** After a menu: how PrintWindow did meanwhile, for the log. */
    private void logPrinting(long now) {
        if (printCount == 0) return;
        double seconds = Math.max(0.001, (now - printFrom) / 1e9);
        log.log(String.format(Locale.ROOT, "While the menu was open: %.1f pictures a second of %s with PrintWindow, %.1f ms each",
                printCount / seconds, label, printNanos / 1e6 / printCount));
        printCount = 0;
        printNanos = 0;
    }

    /** The clean picture, with the cursor drawn in if it's over the window, published. */
    private void compose(long takenAt) {
        if (back == null || back.width != cleanWidth || back.height != cleanHeight) {
            if (back != null) back.free();
            back = new DesktopCapture.Frame(cleanWidth, cleanHeight);
        }
        long bytes = (long) cleanWidth * cleanHeight * 4;
        back.bits.getByteBuffer(0, bytes).put(clean.getByteBuffer(0, bytes));
        readCursor();
        int[] origin = origin();
        cursorDrawn = origin != null && cursorShown && over(origin, cursorX, cursorY);
        if (cursorDrawn) back.drawCursor(origin[0], origin[1], hotspots);
        publish(takenAt);
        lastPublished = System.nanoTime();
    }

    private boolean over(int[] origin, int x, int y) {
        return x >= origin[0] && y >= origin[1] && x < origin[0] + cleanWidth && y < origin[1] + cleanHeight;
    }

    /**
     * Whether the picture must be published again for the cursor: it moved or changed shape over the window, or left
     * it. Moves elsewhere on the desktop don't count (each new picture costs an upload).
     */
    private boolean cursorNeedsRedraw() {
        if (!readCursor()) return false;
        if (cursorDrawn) return true; // it was in the picture: moved, or gone from it
        int[] origin = origin();
        return cursorShown && origin != null && over(origin, cursorX, cursorY);
    }

    /**
     * Where the picture's top-left pixel is on the desktop: Graphics Capture takes the window's visible frame, which
     * is what DWM reports as its extended frame bounds (without the invisible resize borders). Null if unknown.
     */
    private int[] origin() {
        RECT r = new RECT();
        if (Win32.Dwm.I.DwmGetWindowAttribute(Win32.hwnd(window), DWMWA_EXTENDED_FRAME_BOUNDS, r, r.size()) != 0) return null;
        return new int[] {r.left, r.top};
    }

    /** Reads where the cursor is now; true if it moved, changed shape, or appeared or went since the last read. */
    private boolean readCursor() {
        CURSORINFO ci = new CURSORINFO();
        if (!U32.I.GetCursorInfo(ci)) return false;
        boolean shown = (ci.flags & Win32.CURSOR_SHOWING) != 0 && ci.hCursor != null;
        long shape = ci.hCursor == null ? 0 : Pointer.nativeValue(ci.hCursor);
        int x = ci.ptScreenPos.x, y = ci.ptScreenPos.y;
        if (shown == cursorShown && shape == cursorShape && x == cursorX && y == cursorY) return false;
        cursorShown = shown;
        cursorShape = shape;
        cursorX = x;
        cursorY = y;
        return true;
    }

    /** Swaps the picture just drawn with the one on show. */
    private void publish(long takenAt) {
        synchronized (lock) {
            DesktopCapture.Frame done = back;
            back = front;
            front = done;
            fresh = true;
            published++;
            frontTime = takenAt;
        }
    }
}
