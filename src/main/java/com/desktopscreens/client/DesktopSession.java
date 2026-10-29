package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.core.AppWindow;
import com.desktopscreens.core.ClickThrough;
import com.desktopscreens.core.DesktopInput;
import com.desktopscreens.core.Monitor;
import com.desktopscreens.core.Monitors;
import com.desktopscreens.core.PanelMapping;
import com.desktopscreens.core.Win32;
import com.mojang.blaze3d.platform.Window;
//? if >=26.1 {
/*import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import org.joml.Matrix3x2f;
*///?} else {
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
//?}
//? if >=26.2 {
/*import com.mojang.blaze3d.systems.RenderSystem;
*///?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

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
    /** Where the in-place view needs the game window cut open this frame (window pixels), if it's cut ({@link #cutsHole}). */
    private int[] inPlaceHole;
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
    /**
     * The VR panel view ({@link VrMode}): the picture fills the window, which Vivecraft shows on its floating panel,
     * and the screen turns the laser, menu buttons and keyboard into real input ({@link #desktopPoint}). On
     * Minecraft's own monitor too: the real screen can't be seen in a headset.
     */
    private boolean panel;
    /** In the panel view: the mouse and keyboard handover is on (with the test switch, the real ones stay with the game). */
    private boolean panelControl;
    /** In the panel view: where the last frame's picture landed, for {@link #desktopPoint}; null before the first. */
    private PanelMapping mapping;
    /** For the log: the protected-video warning as last shown, its changes since the last note, and when that was. */
    private boolean protectedShown;
    private int protectedChanges;
    private long protectedNotedAt;

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

            /**
             * Every focus change and window move, and why (no titles). Debug only since 2026-09-25, when the window
             * rules had settled: they show with the log level set to debug.
             */
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
        long gameWindow = GameWindow.hwnd(mc);
        Monitor game = Monitors.of(gameWindow);
        Monitor saved = Monitors.byName(choice.monitor); // null if it was unplugged or never chosen
        AppWindow savedWindow = AppWindow.find(choice.window, choice.handle); // null if it isn't open
        if (savedWindow == null) choice.window = "";
        else choice.handle = savedWindow.handle(); // the same window, or another of that program if it was closed
        Monitor start = savedWindow != null ? Monitors.of(savedWindow.handle()) : null;
        if (start == null) start = saved != null ? saved : Monitors.defaultTarget(game);
        Window mcWindow = mc.getWindow();
        int host = DesktopClient.hostScancode();
        DesktopScreens.LOG.info("Desktop view opened: game {}x{}{} on {}, fps limit {}, vsync {}{}{}", mcWindow.getWidth(), mcWindow.getHeight(),
                mc.getWindow().isFullscreen() ? " fullscreen" : "", game, mc.options.framerateLimit().get(), mc.options.enableVsync().get(),
                savedWindow != null ? ", showing one window: " + savedWindow : "",
                host > 0 && host != DesktopInput.DEFAULT_HOST_KEY
                        ? String.format(", host key %s (scan code 0x%X)", DesktopClient.hostKeyName().getString(), host) : "");
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
        boolean closed = windowClosed;
        windowClosed = false;
        return closed;
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

    /** True while the in-place view cuts its hole into the game window ({@link #cutsHole}). */
    boolean cuttingHole() {
        return clickThrough != null && !panel && cutsHole();
    }

    /**
     * Whether the in-place view cuts a hole into the game window, rather than drawing the see-through color: Minecraft
     * 26.2 can draw with Vulkan, and that skipped the color key on the test PC (2026-10-02). With OpenGL, the color
     * key as before.
     */
    private static boolean cutsHole() {
        //? if >=26.2 {
        /*return !"OpenGL".equals(RenderSystem.getDevice().getDeviceInfo().backendName());
        *///?} else {
        return false;
        //?}
    }

    /**
     * After the screen has drawn this frame: cuts the game window open where {@link #drawInPlace} wants it, keeping
     * {@code keep} (what was drawn over the hole, in window pixels). Only when cutting holes.
     */
    void shapeWindow(List<int[]> keep) {
        if (!cuttingHole()) return;
        clickThrough.shape(inPlaceHole != null ? inPlaceHole : new int[4], keep);
    }

    /** True for the monitor Minecraft itself is on. */
    boolean isGameMonitor(Monitor m) {
        return m.sameAs(gameMonitor);
    }

    /** Switches to another monitor, and keeps control if the view had it. */
    void setTarget(Monitor monitor) {
        if (monitor == null || monitor.sameAs(target)) return;
        boolean hadControl = controlling();
        stopInput();
        stopCapturing();
        startCapturing(monitor);
        zoom.reset();
        if (hadControl) startInput();
    }

    /**
     * Whether the framed view applies: it's chosen, and the monitor isn't Minecraft's own, which is always shown in
     * place. The settings preview shows it too.
     */
    boolean framed() {
        return DesktopConfig.framed && !inWorld && !panel && clickThrough == null && !isGameMonitor(target);
    }

    /**
     * Whether the world shows: around the picture in a framed view, around one window on Minecraft's own monitor (only
     * that window is see-through), or all of it while using a screen in the world. Only with a world to show, not
     * from the title screen.
     */
    boolean showsWorld() {
        return !panel && (inWorld || framed() || clickThrough != null && window != null) && mc.level != null;
    }

    void setInWorld(boolean inWorld) {
        this.inWorld = inWorld;
    }

    boolean inWorld() {
        return inWorld;
    }

    void setPanel(boolean panel) {
        this.panel = panel;
    }

    /** The VR panel view, see {@link #panel}. */
    boolean panel() {
        return panel;
    }

    /**
     * In the panel view: the desktop pixel under a point of the screen (GUI units, as Minecraft gives the mouse), or
     * null off the picture or before it was first drawn.
     */
    int[] desktopPoint(double guiX, double guiY) {
        PanelMapping m = mapping;
        if (m == null) return null;
        double scale = mc.getWindow().getGuiScale();
        return m.toDesktop(guiX * scale, guiY * scale);
    }

    /** Zoom only works in the desktop view of other monitors: Minecraft's own is always shown in place, 1:1. */
    boolean canZoom() {
        return !inWorld && !panel && !isGameMonitor(target);
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
     * is handled by the screen, which keeps it on across settings.
     */
    void startInput() {
        if (controlling()) return;
        if (panel) {
            // The picture must show the desktop behind the game window, and clicks there must reach it. Not with the
            // test switch: the tester's mouse has to keep reaching the game window, where it stands in for the laser.
            panelControl = true;
            if (isGameMonitor(target)) {
                LiveCapture.hideForPanel(true);
                if (!VrMode.testing()) startClickThrough();
            }
            if (VrMode.testing()) return;
        } else if (isGameMonitor(target)) {
            startClickThrough();
        }
        windowArea = window == null ? null : window.rectOn(target);
        input.setHostKey(DesktopClient.hostScancode()); // picked in Controls, maybe since the last time
        input.enter(target, window); // after the click-through, so the focus goes to the app behind the game window
    }

    /** Gives the mouse and keyboard back to Minecraft. */
    void stopInput() {
        stopClickThrough(); // first, so the game window can be clicked again once it gets the focus back
        if (panelControl) {
            panelControl = false;
            LiveCapture.hideForPanel(false);
        }
        if (!input.isActive()) return;
        input.exit();
    }

    /** True while the desktop is being used: the real mouse and keyboard control it, or the panel view's do. */
    boolean controlling() {
        return input.isActive() || panelControl;
    }

    private void startClickThrough() {
        clickThrough = new ClickThrough(gameWindow);
        // The real screen shows through the game window now (see drawInPlace); the panel view still draws the picture.
        if (!panel) live.setPausedForView(true);
        LiveCapture.hideGameAgain(gameWindow);
        DesktopScreens.LOG.info("Clicks now go through the game window to the apps behind it ({}{})", clickThrough,
                panel ? "; the VR panel shows the desktop behind it"
                        : cutsHole() ? "; the see-through part is cut out of the window, as the color key doesn't work with Vulkan" : "");
    }

    private void stopClickThrough() {
        if (clickThrough == null) return;
        clickThrough.close();
        clickThrough = null;
        live.setPausedForView(false);
        LiveCapture.hideGameAgain(gameWindow);
        LiveCapture.checkGameWindow(gameWindow); // closing took away the color key browsers may still need
    }

    /** The desktop view itself: draws over the whole window. Nothing while using a screen in the world. */
    void draw(GuiGraphics g) {
        inPlaceHole = null;
        if (inWorld) {
            trackWindow(); // the screen in the world draws the picture, and times it
            return;
        }
        long start = System.nanoTime();
        beginFrame(activity());
        Window mcWindow = mc.getWindow();
        if (clickThrough != null && !panel) drawInPlace(g);
        else drawView(g, 0, 0, mcWindow.getWidth(), mcWindow.getHeight(), panel ? null : zoom);
        stats.drew(System.nanoTime() - start);
    }

    /** The settings panel's live preview, inside an area of the window in window pixels. Never zoomed. */
    void drawPreview(GuiGraphics g, int areaX, int areaY, int areaW, int areaH) {
        long start = System.nanoTime();
        beginFrame("the settings preview");
        drawView(g, areaX, areaY, areaW, areaH, null);
        stats.drew(System.nanoTime() - start);
    }

    /** The picture in an area of the window: all of it, or inside the margin in the framed view. */
    private void drawView(GuiGraphics g, int areaX, int areaY, int areaW, int areaH, Zoom zoom) {
        if (framed()) {
            int m = (int) Math.round(Math.min(areaW, areaH) * DesktopConfig.frameMargin / 100.0);
            drawFitted(g, areaX + m, areaY + m, areaW - 2 * m, areaH - 2 * m, zoom);
        } else {
            drawFitted(g, areaX, areaY, areaW, areaH, zoom);
        }
    }

    private void beginFrame(String activity) {
        trackWindow();
        stats.frame(activity, clickThrough != null && !panel ? FrameStats.SEE_THROUGH : live.method(),
                live.averageMillis());
    }

    /** What the view is doing, for the frame timing in the log. */
    private String activity() {
        String oneWindow = window != null ? ", one window" : "";
        if (panel) return "the VR panel, showing " + target.label() + (clickThrough != null ? " (click-through)" : "") + oneWindow;
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

    /**
     * Draws the newest frame (or the window being shown, cut out of it) and its border inside an area, in window
     * pixels (not GUI units), so the picture lands exactly on pixel boundaries. With a {@code zoom}, part of it,
     * magnified around the pointer.
     */
    private void drawFitted(GuiGraphics g, int areaX, int areaY, int areaW, int areaH, Zoom zoom) {
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
        if (panel) mapping = new PanelMapping(x, y, w, h, crop[0] + srcX, crop[1] + srcY, srcW, srcH, target.x, target.y);
        float toGui = (float) (1.0 / mc.getWindow().getGuiScale());
        GuiDraw.push(g);
        GuiDraw.scale(g, toGui, toGui);
        drawPicture(g, x, y, w, h, crop[0] + srcX, crop[1] + srcY, srcW, srcH, scaling != Scaling.SMOOTH);
        int[] cover = GameCover.place(live.gameArea(), crop[0] + srcX, crop[1] + srcY, srcW, srcH, x, y, w, h);
        if (cover != null) GameCover.drawGui(g, cover);
        // Around the picture, but never outside the area: where there's no room, it goes over the picture's edge.
        int left = Math.max(areaX, x - border), top = Math.max(areaY, y - border);
        int right = Math.min(areaX + areaW, x + w + border), bottom = Math.min(areaY + areaH, y + h + border);
        BorderRenderer.draw(g, left + border, top + border, right - left - 2 * border, bottom - top - 2 * border);
        GuiDraw.pop(g);
    }

    /**
     * While clicks go through the game window, the part of it over the monitor is see-through: it's drawn in
     * {@link ClickThrough#SEE_THROUGH_RGB}, and Windows shows the real screen there. So what you see is exactly
     * where you click, live, and protected video plays too, which a captured picture would show black. Nothing is
     * captured meanwhile. Works in windowed mode too. The border goes over the window's edge, as a reminder that
     * this is still the game. With one window shown, only that window is see-through, the world shows around it,
     * and the border goes around the window. Where the color key is skipped (Vulkan), the same part is cut out of
     * the window instead ({@link #shapeWindow}).
     */
    private void drawInPlace(GuiGraphics g) {
        Window mcWindow = mc.getWindow();
        int[] origin = Win32.clientOrigin(gameWindow);
        boolean oneWindow = window != null && windowArea != null;
        int[] hole = oneWindow ? windowArea : new int[] {target.x, target.y, target.x + target.width, target.y + target.height};
        // Only the part of the game window that's over it; a game window hanging off the monitor's edge shows black there.
        int left = Math.max(0, hole[0] - origin[0]), top = Math.max(0, hole[1] - origin[1]);
        int right = Math.min(mcWindow.getWidth(), hole[2] - origin[0]), bottom = Math.min(mcWindow.getHeight(), hole[3] - origin[1]);
        if (right <= left || bottom <= top) return;
        float toGui = (float) (1.0 / mcWindow.getGuiScale());
        GuiDraw.push(g);
        GuiDraw.scale(g, toGui, toGui);
        g.fill(left, top, right, bottom, 0xFF000000 | ClickThrough.SEE_THROUGH_RGB);
        int border = BorderRenderer.thickness();
        if (oneWindow) BorderRenderer.draw(g, left, top, right - left, bottom - top);
        else BorderRenderer.draw(g, border, border, mcWindow.getWidth() - 2 * border, mcWindow.getHeight() - 2 * border);
        GuiDraw.pop(g);
        // When the hole is cut instead (shapeWindow), the border stays: around the window, or along the game window's edges.
        inPlaceHole = oneWindow ? new int[] {left, top, right, bottom}
                : new int[] {Math.max(left, border), Math.max(top, border),
                        Math.min(right, mcWindow.getWidth() - border), Math.min(bottom, mcWindow.getHeight() - border)};
    }

    /** Draws the {@code src} part of the frame (desktop pixels) into a rectangle in window pixels. */
    private void drawPicture(GuiGraphics g, int x, int y, int w, int h, double srcX, double srcY, double srcW, double srcH, boolean sharp) {
        int frameWidth = live.frameWidth(), frameHeight = live.frameHeight();
        if (!drawPicture(g, live.texture(), frameWidth, frameHeight, x, y, w, h, srcX, srcY, srcW, srcH, sharp)) {
            //? if <26.1 {
            g.blit(live.location(), x, y, w, h, (float) srcX, (float) srcY, (int) Math.round(srcW), (int) Math.round(srcH),
                    frameWidth, frameHeight);
            //?}
        }
    }

    /**
     * Draws the {@code src} part (desktop pixels) of a picture texture of {@code frameWidth} x {@code frameHeight} into
     * a rectangle, with the desktop shader ({@link Scaling}): for the desktop view, its settings preview and
     * picture-in-picture. False if the shader isn't there (it failed to load), and nothing was drawn.
     */
    static boolean drawPicture(GuiGraphics g, DesktopTexture texture, int frameWidth, int frameHeight, int x, int y, int w, int h,
                               double srcX, double srcY, double srcW, double srcH, boolean sharp) {
        float u0 = (float) (srcX / frameWidth), v0 = (float) (srcY / frameHeight);
        float u1 = (float) ((srcX + srcW) / frameWidth), v1 = (float) ((srcY + srcH) / frameHeight);
        //? if >=26.1 {
        /*// Without the sharp shader (smooth scaling, or it didn't compile), Minecraft's own: plain bilinear.
        RenderPipeline pipeline = sharp ? DesktopShaders.sharp() : null;
        if (pipeline == null) pipeline = RenderPipelines.GUI_TEXTURED;
        GuiDraw.submit(g, new BlitRenderState(pipeline, TextureSetup.singleTexture(texture.getTextureView(), texture.getSampler()),
                new Matrix3x2f(g.pose()), x, y, x + w, y + h, u0, u1, v0, v1, 0xFFFFFFFF, GuiDraw.scissor(g)));
        return true;
        *///?} else {
        ShaderInstance shader = DesktopShaders.desktop;
        if (shader == null) return false;
        Matrix4f pose = g.pose().last().pose();
        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, texture.getId());
        shader.safeGetUniform("TexSize").set((float) frameWidth, (float) frameHeight);
        shader.safeGetUniform("Scale").set((float) (w / srcW), (float) (h / srcH));
        shader.safeGetUniform("Sharp").set(sharp ? 1f : 0f);
        BufferBuilder buffer = Vertices.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        Vertices.posUv(buffer, pose, x, y, 0, u0, v0);
        Vertices.posUv(buffer, pose, x, y + h, 0, u0, v1);
        Vertices.posUv(buffer, pose, x + w, y + h, 0, u1, v1);
        Vertices.posUv(buffer, pose, x + w, y, 0, u1, v0);
        Vertices.draw(buffer);
        return true;
        //?}
    }

    /** The mouse pointer relative to the target monitor, in desktop pixels, or null if Windows won't say. */
    private int[] pointer() {
        int[] p = Win32.cursorPos();
        return p == null ? null : new int[] {p[0] - target.x, p[1] - target.y};
    }

    /** The last capture error, or null while capture works or isn't needed (the real screen shows through the game). */
    String error() {
        return clickThrough != null && !panel ? null : live.error();
    }

    void close() {
        if (closed) return;
        closed = true;
        stats.flush();
        if (protectedChanges > 0) noteProtected(System.nanoTime());
        stopInput();
        stopCapturing();
    }

    private void startCapturing(Monitor monitor) {
        target = monitor;
        live = LiveCapture.acquire(mc, monitor);
        live.setPausedForView(clickThrough != null && !panel);
    }

    /** True while protected video (DRM) shows black in the picture, which Windows does in every screen capture. */
    boolean protectedContentHidden() {
        boolean hidden = (clickThrough == null || panel) && live.protectedContentHidden();
        if (hidden != protectedShown) {
            protectedShown = hidden;
            protectedChanges++;
            // It flickered with no protected video anywhere (2026-10-29): the log says when, where and with what, once
            // now and then at most every 10 s, as it may change many times a second.
            long now = System.nanoTime();
            if (protectedNotedAt == 0 || now - protectedNotedAt >= 10_000_000_000L) noteProtected(now);
        }
        return hidden;
    }

    private void noteProtected(long now) {
        DesktopScreens.LOG.info("Windows says protected video is blacked out in the picture of {}: {} ({} changes since the last note; "
                        + "the game window {} on this monitor and {} from capture; {})", target.label(), protectedShown ? "yes" : "no",
                protectedChanges, Win32.overlaps(gameWindow, target) ? "is" : "isn't",
                Win32.excludedFromCapture(gameWindow) ? "hidden" : "not hidden", live.method());
        protectedChanges = 0;
        protectedNotedAt = now;
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
