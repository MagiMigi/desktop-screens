package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.core.AppWindow;
import com.desktopscreens.core.WindowCapture;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.Map;

/**
 * One window's own capture ({@link WindowCapture}, Windows Graphics Capture) and its texture, shared by everything that
 * shows that window apart from the desktop view: screens in the world, and later sharing and picture-in-picture.
 * Unlike {@link LiveCapture} it needs no monitor captured and never hides the game from screen capture. Render thread
 * only.
 */
final class LiveWindow {
    private static final Map<Long, LiveWindow> OPEN = new HashMap<>();

    private final Minecraft mc;
    private final AppWindow window;
    private final DesktopTexture texture = new DesktopTexture();
    private final WindowCapture capture;
    private int users;
    private int frameWidth, frameHeight;

    private LiveWindow(Minecraft mc, AppWindow window) {
        this.mc = mc;
        this.window = window;
        capture = new WindowCapture(window.handle(), window.toString(), graphicsCaptureAllowed(mc),
                message -> DesktopScreens.LOG.info("Capture: {}", message));
    }

    /**
     * Not while the fullscreen game shows a menu, whose cursor Windows then sometimes doesn't draw at all (see
     * {@link WindowCapture#setGraphicsCaptureAllowed}); the picture waits meanwhile. The desktop view itself doesn't
     * count: its pointer is on the desktop. Windowed, the game is drawn by Windows' compositor, cursor and all.
     */
    static boolean graphicsCaptureAllowed(Minecraft mc) {
        return !mc.isFullScreen() || mc.currentScreen == null
                || mc.currentScreen instanceof DesktopScreen && ((DesktopScreen) mc.currentScreen).usingDesktop();
    }

    /**
     * Every client tick, for all open window captures: whether they may run ({@link #graphicsCaptureAllowed}), also
     * while nothing draws them (a screen out of sight keeps its capture a few seconds).
     */
    static void tickAll(Minecraft mc) {
        if (OPEN.isEmpty()) return;
        boolean allowed = graphicsCaptureAllowed(mc);
        for (LiveWindow live : OPEN.values()) live.capture.setGraphicsCaptureAllowed(allowed);
    }

    /** The capture of {@code window}, started if nothing shows it yet. Give it back with {@link #release}. */
    static LiveWindow acquire(Minecraft mc, AppWindow window) {
        LiveWindow live = OPEN.get(window.handle());
        if (live == null) {
            live = new LiveWindow(mc, window);
            OPEN.put(window.handle(), live);
        }
        live.users++;
        return live;
    }

    /** Stops the capture once the last user gives it back. */
    void release() {
        if (--users > 0) return;
        OPEN.remove(window.handle());
        capture.close();
        texture.delete();
        LiveCapture.checkGameWindow(GameWindow.hwnd());
    }

    /** Whether any window is captured: browsers behind a windowed game must keep drawing then ({@link LiveCapture}). */
    static boolean anyOpen() {
        return !OPEN.isEmpty();
    }

    boolean shows(AppWindow w) {
        return window.sameAs(w);
    }

    /** True if Graphics Capture can't capture it (too old a Windows, or the window refuses): show it another way. */
    boolean failed() {
        return capture.error() != null && frameWidth == 0;
    }

    DesktopTexture texture() {
        return texture;
    }

    int frameWidth() {
        return frameWidth;
    }

    int frameHeight() {
        return frameHeight;
    }

    double averageMillis() {
        return capture.averageMillis();
    }

    /** See {@link WindowCapture#capturedPictures}. */
    long capturedPictures() {
        return capture.capturedPictures();
    }

    /** See {@link WindowCapture#picturesInMenus}. */
    boolean picturesInMenus() {
        return capture.picturesInMenus();
    }

    /** For sharing, which reads the pictures on its own thread. */
    WindowCapture capture() {
        return capture;
    }

    /** For a user that doesn't draw (sharing), every tick: what {@link #update} keeps up besides the upload. */
    void refresh() {
        LiveCapture.checkGameWindow(GameWindow.hwnd());
    }

    /** Uploads the newest picture, if there's a new one. Call before drawing, every frame. Returns how long that took, or 0. */
    long update() {
        // A browser covered only by a windowed game would stop drawing, and its picture freeze.
        LiveCapture.checkGameWindow(GameWindow.hwnd());
        capture.setGraphicsCaptureAllowed(graphicsCaptureAllowed(mc)); // every frame too: a menu opens between ticks
        long start = System.nanoTime();
        return capture.consumeLatest(this::upload) ? System.nanoTime() - start : 0;
    }

    private void upload(long bgraAddress, int width, int height) {
        texture.upload(bgraAddress, width, height);
        frameWidth = width;
        frameHeight = height;
    }
}
