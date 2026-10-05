package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.core.AppWindow;
import com.desktopscreens.core.ClickThrough;
import com.desktopscreens.core.DesktopInput;
import com.desktopscreens.core.Monitor;
import com.desktopscreens.core.Monitors;
import com.desktopscreens.core.Win32;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/** Everything one open desktop view needs: capture, texture and the mouse/keyboard handover. */
final class DesktopSession {
    private final Minecraft mc;
    private final long gameWindow;
    /** The monitor Minecraft is on. Clicks would land on the game window there, so they go through it, see {@link ClickThrough}. */
    private final Monitor gameMonitor;
    private final DesktopInput input;
    /** What it shows, and where changes to that go: the tablet's choice, or the screen's being used. */
    private final SourceChoice choice;

    private Monitor target;
    /** The target's capture and texture, shared with the screens in the world that show it. */
    private LiveCapture live;
    private boolean closed;
    /** Only while controlling the game's own monitor. */
    private ClickThrough clickThrough;
    /**
     * Just one window instead of the whole monitor (host key + W), or null. The picture is the monitor's, cut down
     * to the window, so its menus and dialogs show wherever they fall inside it.
     */
    private AppWindow window;
    /** The window's part of the monitor this frame, {left, top, right, bottom} in desktop pixels; null while it's minimized. */
    private int[] windowArea;
    private boolean windowClosed;
    private final Zoom zoom = new Zoom();
    private final FrameStats stats = new FrameStats("Desktop view");
    /**
     * Using a screen in the world (G while looking at it, another monitor than Minecraft's): nothing is drawn over the
     * game, you watch the screen in the world while the mouse and keyboard control its monitor.
     */
    private boolean inWorld;

    private DesktopSession(Minecraft mc, long gameWindow, Monitor game, Monitor target, SourceChoice choice) {
        this.mc = mc;
        this.gameWindow = gameWindow;
        this.gameMonitor = game;
        this.choice = choice;
        this.input = new DesktopInput(gameWindow, new DesktopInput.Listener() {
            @Override
            public void onHostKey() {
                DesktopClient.onHostKey();
            }

            @Override
            public void onGameChosen() {
                DesktopClient.onGameChosen();
            }

            @Override
            public void onHostCombo(int virtualKey) {
                DesktopClient.onHostCombo(virtualKey);
            }

            @Override
            public void onHostWheel(int delta) {
                DesktopClient.onHostWheel(delta);
            }

            /** Every focus change and window move, and why (no titles): debug only, in logs/debug.log. */
            @Override
            public void log(String message) {
                DesktopScreens.LOG.debug("Windows: {}", message);
            }
        });
        startCapturing(target);
    }

    /** The desktop view of G and the tablet: what the tablet's choice shows. */
    static DesktopSession open(Minecraft mc) {
        DesktopConfig.load();
        return open(mc, DesktopConfig.tablet);
    }

    /** A desktop view showing {@code choice} (a screen's, while it's used), and changing it. */
    static DesktopSession open(Minecraft mc, SourceChoice choice) {
        long gameWindow = GameWindow.hwnd();
        Monitor game = Monitors.of(gameWindow);
        Monitor saved = Monitors.byName(choice.monitor); // null if it was unplugged or never chosen
        AppWindow savedWindow = AppWindow.find(choice.window, choice.handle); // null if it isn't open
        if (savedWindow == null) choice.window = "";
        else choice.handle = savedWindow.handle(); // the same window, or another of that program if it was closed
        Monitor start = savedWindow != null ? Monitors.of(savedWindow.handle()) : null;
        if (start == null) start = saved != null ? saved : Monitors.defaultTarget(game);
        int host = DesktopClient.hostScancode();
        DesktopScreens.LOG.info("Desktop view opened: game {}x{}{} on {}, fps limit {}, vsync {}{}{}", mc.displayWidth, mc.displayHeight,
                mc.isFullScreen() ? " fullscreen" : "", game, mc.gameSettings.limitFramerate, mc.gameSettings.enableVsync,
                savedWindow != null ? ", showing one window: " + savedWindow : "",
                host > 0 && host != DesktopInput.DEFAULT_HOST_KEY
                        ? String.format(", host key %s (scan code 0x%X)", DesktopClient.hostKeyName().getUnformattedText(), host) : "");
        DesktopSession session = new DesktopSession(mc, gameWindow, game, start, choice);
        session.window = savedWindow;
        return session;
    }

    /** What it shows, and where changes to that go. */
    SourceChoice choice() {
        return choice;
    }

    Monitor target() {
        return target;
    }

    /** The one window being shown, or null for the whole monitor. */
    AppWindow window() {
        return window;
    }

    /** What the view shows, for the hints: the window's title, or the monitor. */
    String sourceLabel() {
        return window != null ? window.label() : target.label();
    }

    /** The app window under the mouse pointer right now, or null (the desktop, the taskbar...). */
    AppWindow windowUnderPointer() {
        int[] p = Win32.cursorPos();
        return p == null ? null : AppWindow.under(p[0], p[1]);
    }

    /**
     * Shows just this window, on whatever monitor it's on, or the whole monitor again with null. Keeps control if
     * the view had it, and gives the window the keyboard.
     */
    void setWindow(AppWindow w) {
        window = w;
        windowArea = null;
        zoom.reset();
        choice.window = w == null ? "" : w.key();
        choice.handle = w == null ? 0 : w.handle();
        if (w == null) DesktopScreens.LOG.info("Showing the whole monitor again");
        else DesktopScreens.LOG.info("Showing one window: {}", w);
        Monitor on = w == null || w.isOn(target) ? null : Monitors.of(w.handle());
        if (on != null) choice.monitor = on.name;
        choice.changed();
        if (on != null) {
            setTarget(on); // restarts the input, which gives the window the keyboard
            return;
        }
        if (w != null) windowArea = w.rectOn(target);
        if (input.isActive()) {
            input.show(w);
            if (w != null) input.focus(w);
        }
    }

    /** The size of what's shown, in desktop pixels: the one window (while it isn't minimized), else the whole monitor. */
    int[] pictureSize() {
        int[] area = window != null ? window.rectOn(target) : null;
        return area != null ? new int[] {area[2] - area[0], area[3] - area[1]} : new int[] {target.width, target.height};
    }

    /** True once after the window being shown was closed (the view went back to the whole monitor). */
    boolean takeWindowClosed() {
        boolean was = windowClosed;
        windowClosed = false;
        return was;
    }

    /** Follows the window being shown: onto another monitor, where it is now for drawing, and until it's closed. */
    private void trackWindow() {
        if (window == null) return;
        if (!window.alive()) {
            DesktopScreens.LOG.info("The window shown ({}) was closed; showing the whole monitor", window);
            window = null;
            windowArea = null;
            windowClosed = true;
            choice.window = "";
            choice.handle = 0;
            choice.changed();
            input.show(null);
            return;
        }
        if (!window.isOn(target)) {
            Monitor on = Monitors.of(window.handle());
            if (on != null) {
                DesktopScreens.LOG.info("The window shown ({}) moved to {}", window, on);
                choice.monitor = on.name;
                choice.changed();
                setTarget(on);
            }
        }
        // Only for drawing: the mouse lock reads the window's frame itself, fresh every time.
        windowArea = window.rectOn(target);
    }

    /** True while clicks go through the game window to the apps behind it. */
    boolean clickingThrough() {
        return clickThrough != null;
    }

    /** True for the monitor Minecraft itself is on. */
    boolean isGameMonitor(Monitor m) {
        return m.sameAs(gameMonitor);
    }

    /** Switches to another monitor, and keeps control if the view had it. */
    void setTarget(Monitor monitor) {
        if (monitor == null || monitor.sameAs(target)) return;
        boolean hadControl = input.isActive();
        stopInput();
        stopCapturing();
        startCapturing(monitor);
        zoom.reset();
        if (hadControl) startInput();
    }

    /**
     * Whether the framed view applies: it's chosen, and the monitor isn't Minecraft's own, which is always shown in
     * place.
     */
    boolean framed() {
        return DesktopConfig.framed && !inWorld && clickThrough == null && !isGameMonitor(target);
    }

    /**
     * Whether the world shows: around the picture in a framed view, around one window on Minecraft's own monitor (only
     * that window is see-through), or all of it while using a screen in the world. Only with a world to show, not
     * from the title screen.
     */
    boolean showsWorld() {
        return (inWorld || framed() || clickThrough != null && window != null) && mc.world != null;
    }

    void setInWorld(boolean inWorld) {
        this.inWorld = inWorld;
    }

    boolean inWorld() {
        return inWorld;
    }

    /** Zoom only works in the desktop view of other monitors: Minecraft's own is always shown in place, 1:1. */
    boolean canZoom() {
        return !inWorld && !isGameMonitor(target);
    }

    void zoomBy(int steps) {
        zoom.zoomBy(steps);
    }

    /** Right Ctrl + mouse wheel: returns the whole notches turned so far. */
    int wheelNotches(int delta) {
        return zoom.notches(delta);
    }

    void resetZoom() {
        zoom.reset();
    }

    boolean zoomed() {
        return zoom.active();
    }

    /** The zoom, like "2x", for the hint. */
    String zoomLabel() {
        return zoom.label();
    }

    /**
     * Hands the real mouse and keyboard over to the target monitor. On Minecraft's own monitor the clicks go
     * through the game window. Needs a screen open, so Minecraft has let go of the mouse; {@link FocusHandOff}
     * is handled by the screen.
     */
    void startInput() {
        if (input.isActive()) return;
        if (isGameMonitor(target)) startClickThrough();
        windowArea = window == null ? null : window.rectOn(target);
        input.setHostKey(DesktopClient.hostScancode()); // picked in Controls, maybe since the last time
        input.enter(target, window); // after the click-through, so the focus goes to the app behind the game window
    }

    /** Gives the mouse and keyboard back to Minecraft. */
    void stopInput() {
        stopClickThrough(); // first, so the game window can be clicked again once it gets the focus back
        if (!input.isActive()) return;
        input.exit();
    }

    /** True while the real mouse and keyboard control the desktop. */
    boolean controlling() {
        return input.isActive();
    }

    private void startClickThrough() {
        clickThrough = new ClickThrough(gameWindow);
        live.setPausedForView(true); // the real screen shows through the game window now, see drawInPlace
        LiveCapture.hideGameAgain(gameWindow);
        DesktopScreens.LOG.info("Clicks now go through the game window to the apps behind it ({})", clickThrough);
    }

    private void stopClickThrough() {
        if (clickThrough == null) return;
        clickThrough.close();
        clickThrough = null;
        live.setPausedForView(false);
        LiveCapture.hideGameAgain(gameWindow);
        LiveCapture.checkGameWindow(gameWindow); // closing took away the color key browsers may still need
    }

    /** The desktop view itself: draws over the whole window, in window pixels. Nothing while using a screen in the world. */
    void draw() {
        if (inWorld) {
            trackWindow(); // the screen in the world draws the picture, and times it
            return;
        }
        long start = System.nanoTime();
        beginFrame(activity());
        if (clickThrough != null) drawInPlace();
        else drawView(0, 0, mc.displayWidth, mc.displayHeight, zoom);
        stats.drew(System.nanoTime() - start);
    }

    /** The settings panel's live preview, inside an area of the window in window pixels. Never zoomed. */
    void drawPreview(int areaX, int areaY, int areaW, int areaH) {
        long start = System.nanoTime();
        beginFrame("the settings preview");
        drawView(areaX, areaY, areaW, areaH, null);
        stats.drew(System.nanoTime() - start);
    }

    /** The picture in an area of the window: all of it, or inside the margin in the framed view. */
    private void drawView(int areaX, int areaY, int areaW, int areaH, Zoom zoom) {
        if (framed()) {
            int m = (int) Math.round(Math.min(areaW, areaH) * DesktopConfig.frameMargin / 100.0);
            drawFitted(areaX + m, areaY + m, areaW - 2 * m, areaH - 2 * m, zoom);
        } else {
            drawFitted(areaX, areaY, areaW, areaH, zoom);
        }
    }

    private void beginFrame(String activity) {
        trackWindow();
        stats.frame(activity, clickThrough != null ? FrameStats.SEE_THROUGH : live.method(), live.averageMillis());
    }

    /** What the view is doing, for the frame timing in the log. */
    private String activity() {
        String oneWindow = window != null ? ", one window" : "";
        if (clickThrough != null) return "controlling the game's monitor (click-through)" + oneWindow;
        return "controlling " + target.label() + (framed() ? ", framed" : "") + oneWindow;
    }

    /** The part of the frame to show, {x, y, width, height} in desktop pixels: the window being shown, or all of it. */
    private int[] crop() {
        int frameWidth = live.frameWidth(), frameHeight = live.frameHeight();
        if (window != null && windowArea != null) {
            int x = Math.max(0, windowArea[0] - target.x), y = Math.max(0, windowArea[1] - target.y);
            int right = Math.min(frameWidth, windowArea[2] - target.x), bottom = Math.min(frameHeight, windowArea[3] - target.y);
            if (right > x && bottom > y) return new int[] {x, y, right - x, bottom - y};
        }
        return new int[] {0, 0, frameWidth, frameHeight};
    }

    /** Scales the GUI's units down to window pixels, until {@link #endPixels}. */
    private void beginPixels() {
        float toGui = 1f / new ScaledResolution(mc).getScaleFactor();
        GlStateManager.pushMatrix();
        GlStateManager.scale(toGui, toGui, 1);
    }

    private static void endPixels() {
        GlStateManager.popMatrix();
    }

    /**
     * Draws the newest frame (or the window being shown, cut out of it) and its border inside an area, in window
     * pixels (not GUI units), so the picture lands exactly on pixel boundaries. With a {@code zoom}, part of it,
     * magnified around the pointer.
     */
    private void drawFitted(int areaX, int areaY, int areaW, int areaH, Zoom zoom) {
        long uploadNanos = live.update();
        if (uploadNanos > 0) stats.uploaded(uploadNanos);
        if (live.frameWidth() == 0) return;
        int[] crop = crop();
        int cropW = crop[2], cropH = crop[3];
        Scaling scaling = DesktopConfig.scaling;
        int border = BorderRenderer.thickness();
        int innerX = areaX + border, innerY = areaY + border;
        int innerW = Math.max(1, areaW - 2 * border), innerH = Math.max(1, areaH - 2 * border);
        int[] r = scaling.layout(cropW, cropH, innerW, innerH);
        int x = innerX + r[0], y = innerY + r[1], w = r[2], h = r[3];
        if (w < cropW && cropW <= areaW && cropH <= areaH) {
            // Only the border keeps it from fitting 1:1, and shrinking it by a few pixels would blur all of it.
            // Draw it 1:1 instead, and let the border cover its outer edge.
            x = areaX + (areaW - cropW) / 2;
            y = areaY + (areaH - cropH) / 2;
            w = cropW;
            h = cropH;
        }
        double srcX = 0, srcY = 0, srcW = cropW, srcH = cropH;
        if (zoom != null) {
            int[] pointer = pointer();
            if (pointer != null) {
                pointer[0] -= crop[0];
                pointer[1] -= crop[1];
            }
            // Every frame, zoomed or not, so the first zoom step knows where the pointer was.
            Zoom.Placement p = zoom.place(innerX, innerY, innerW, innerH, cropW, cropH, w / (double) cropW, pointer);
            if (zoom.active()) {
                x = p.x();
                y = p.y();
                w = p.w();
                h = p.h();
                srcX = p.srcX();
                srcY = p.srcY();
                srcW = p.srcW();
                srcH = p.srcH();
            }
        }
        beginPixels();
        drawPicture(live.texture(), live.frameWidth(), live.frameHeight(), x, y, w, h,
                crop[0] + srcX, crop[1] + srcY, srcW, srcH, scaling != Scaling.SMOOTH);
        int[] cover = GameCover.place(live.gameArea(), crop[0] + srcX, crop[1] + srcY, srcW, srcH, x, y, w, h);
        if (cover != null) GameCover.drawGui(cover);
        // Around the picture, but never outside the area: where there's no room, it goes over the picture's edge.
        int left = Math.max(areaX, x - border), top = Math.max(areaY, y - border);
        int right = Math.min(areaX + areaW, x + w + border), bottom = Math.min(areaY + areaH, y + h + border);
        BorderRenderer.draw(left + border, top + border, right - left - 2 * border, bottom - top - 2 * border);
        endPixels();
    }

    /**
     * While clicks go through the game window, the part of it over the monitor is see-through: it's drawn in
     * {@link ClickThrough#SEE_THROUGH_RGB}, and Windows shows the real screen there. So what you see is exactly
     * where you click, live, and protected video plays too, which a captured picture would show black. Nothing is
     * captured meanwhile. Works in windowed mode too. The border goes over the window's edge, as a reminder that
     * this is still the game. With one window shown, only that window is see-through, the world shows around it,
     * and the border goes around the window.
     */
    private void drawInPlace() {
        int[] origin = Win32.clientOrigin(gameWindow);
        boolean oneWindow = window != null && windowArea != null;
        int[] hole = oneWindow ? windowArea : new int[] {target.x, target.y, target.x + target.width, target.y + target.height};
        // Only the part of the game window that's over it; a game window hanging off the monitor's edge shows black there.
        int left = Math.max(0, hole[0] - origin[0]), top = Math.max(0, hole[1] - origin[1]);
        int right = Math.min(mc.displayWidth, hole[2] - origin[0]), bottom = Math.min(mc.displayHeight, hole[3] - origin[1]);
        if (right <= left || bottom <= top) return;
        beginPixels();
        Gui.drawRect(left, top, right, bottom, 0xFF000000 | ClickThrough.SEE_THROUGH_RGB);
        int border = BorderRenderer.thickness();
        if (oneWindow) BorderRenderer.draw(left, top, right - left, bottom - top);
        else BorderRenderer.draw(border, border, mc.displayWidth - 2 * border, mc.displayHeight - 2 * border);
        endPixels();
    }

    /**
     * Draws the {@code src} part (desktop pixels) of a picture texture of {@code frameWidth} x {@code frameHeight} into
     * a rectangle, with the desktop shader ({@link Scaling}), or plainly if there's none.
     */
    static void drawPicture(DesktopTexture texture, int frameWidth, int frameHeight, int x, int y, int w, int h,
                            double srcX, double srcY, double srcW, double srcH, boolean sharp) {
        float u0 = (float) (srcX / frameWidth), v0 = (float) (srcY / frameHeight);
        float u1 = (float) ((srcX + srcW) / frameWidth), v1 = (float) ((srcY + srcH) / frameHeight);
        GlStateManager.enableTexture2D();
        GlStateManager.bindTexture(texture.id());
        GlStateManager.color(1, 1, 1, 1);
        boolean shader = DesktopShaders.use(frameWidth, frameHeight, (float) (w / srcW), (float) (h / srcH), sharp);
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        buffer.pos(x, y, 0).tex(u0, v0).endVertex();
        buffer.pos(x, y + h, 0).tex(u0, v1).endVertex();
        buffer.pos(x + w, y + h, 0).tex(u1, v1).endVertex();
        buffer.pos(x + w, y, 0).tex(u1, v0).endVertex();
        tessellator.draw();
        if (shader) DesktopShaders.stop();
    }

    /** The mouse pointer relative to the target monitor, in desktop pixels, or null if Windows won't say. */
    private int[] pointer() {
        int[] p = Win32.cursorPos();
        return p == null ? null : new int[] {p[0] - target.x, p[1] - target.y};
    }

    /** The last capture error, or null while capture works or isn't needed (the real screen shows through the game). */
    String error() {
        return clickThrough != null ? null : live.error();
    }

    void close() {
        if (closed) return;
        closed = true;
        stats.flush();
        stopInput();
        stopCapturing();
    }

    private void startCapturing(Monitor monitor) {
        target = monitor;
        live = LiveCapture.acquire(mc, monitor);
        live.setPausedForView(clickThrough != null);
    }

    /** True while protected video (DRM) shows black in the picture, which Windows does in every screen capture. */
    boolean protectedContentHidden() {
        return clickThrough == null && live.protectedContentHidden();
    }

    /** Same monitor, fresh capture: after switching between fast capture and GDI. The last frame stays up meanwhile. */
    void restartCapture() {
        live.restart();
    }

    private void stopCapturing() {
        live.setPausedForView(false);
        live.release();
    }
}
