package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.core.AppWindow;
import com.desktopscreens.core.Monitor;
import com.desktopscreens.core.Monitors;
import com.desktopscreens.core.Win32;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import org.lwjgl.glfw.GLFWNativeWin32;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The picture on screens in the world: what each screen's {@link SourceChoice} shows (its own, or the tablet's), and
 * picture-in-picture's ({@link PictureInPicture}). One view per thing shown, shared by everything that shows it; its
 * monitor's capture is shared with the desktop view too ({@link LiveCapture}). A view only captures while something
 * showing it is being drawn, and a few seconds after, so turning around doesn't restart it. Its timing lines in the
 * log say "Screens in the world" for picture-in-picture too. Render thread only.
 */
final class WorldScreens {
    private static final long LINGER_NANOS = 3_000_000_000L, SOURCE_CHECK_NANOS = 1_000_000_000L, RETRY_NANOS = 10_000_000_000L;
    /** After a menu, a window's own capture gets this long to deliver a new picture before its old one shows again. */
    private static final long RESUME_NANOS = 1_000_000_000L;

    /**
     * The newest picture: its texture (as a render type for the world, and its GL id for the GUI) and size, the part
     * to show, in desktop pixels, and where the game itself shows in it ({@link GameCover}), or null. A new one every
     * frame.
     */
    record Picture(RenderType renderType, int textureId, int frameWidth, int frameHeight, int cropX, int cropY, int cropW, int cropH, int[] game) {}

    /** One thing shown on screens: a monitor, or one window on it. */
    private static final class View {
        final String monitorName, windowKey;
        final long windowHandle;
        final FrameStats stats = new FrameStats("Screens in the world");
        LiveCapture live;
        /** A window's own capture; null for a monitor, or after it failed ({@link #cutOut}). */
        LiveWindow liveWindow;
        /** Graphics Capture couldn't take this window: its monitor's picture cut to its frame instead (what covers it shows). */
        boolean cutOut;
        /** Its monitor, for while the window's own capture pauses for a menu ({@link #menuPicture}). */
        LiveCapture menuLive;
        /** After a menu: the window's own pictures so far, and when it closed; -1 while one is open. */
        long resumeFrom = -1, resumedAt;
        AppWindow window;
        /** Where the choice points (see {@link #source}), worked out at most once a second. */
        Monitor monitor;
        long frameKey = Long.MIN_VALUE, lastWanted, sourceCheckedAt;
        Picture picture;

        View(SourceChoice choice) {
            monitorName = choice.monitor;
            windowKey = choice.window;
            windowHandle = choice.handle;
        }
    }

    private static final Map<String, View> VIEWS = new HashMap<>();
    private static long failedAt;

    private WorldScreens() {}

    /** For the renderer, while it draws {@code group}, which is on for this player. The same all frame; null until there is one. */
    static Picture picture(Minecraft mc, ScreenGroups.Group group) {
        // The desktop view covers the world, except where it leaves it visible around the picture.
        if (mc.screen instanceof DesktopScreen screen && !screen.showsWorld()) return null;
        return picture(mc, SourceChoice.of(mc.level, group));
    }

    /**
     * What {@code choice} shows, for whatever draws it this frame (screens, picture-in-picture); captured while asked
     * for, and a few seconds after. The same all frame; null until there is one, and off Windows.
     */
    static Picture picture(Minecraft mc, SourceChoice choice) {
        if (!Win32.SUPPORTED) return null;
        View view = view(choice);
        long key = mc.getFrameTimeNs(); // the last frame's length in nanoseconds, so it changes every frame
        if (key == view.frameKey) return view.picture;
        view.frameKey = key;
        view.picture = null;
        long now = System.nanoTime();
        view.lastWanted = now;
        if (failedAt != 0 && now - failedAt < RETRY_NANOS) return null;
        try {
            view.picture = update(mc, view, now);
        } catch (RuntimeException | LinkageError e) {
            DesktopScreens.LOG.error("Screens in the world: couldn't show the desktop", e);
            failedAt = now;
            stop(view);
        }
        return view.picture;
    }

    private static View view(SourceChoice choice) {
        return VIEWS.computeIfAbsent(choice.key(), k -> new View(choice));
    }

    private static Picture update(Minecraft mc, View view, long now) {
        Monitor wanted = source(mc, view);
        if (view.window != null && !view.cutOut) {
            // The window by itself, so it shows while other windows or the game cover it.
            if (view.liveWindow == null || !view.liveWindow.shows(view.window)) {
                stop(view);
                view.liveWindow = LiveWindow.acquire(mc, view.window);
                DesktopScreens.LOG.info("Screens in the world: showing {}", view.window);
            }
            LiveWindow lw = view.liveWindow;
            if (!lw.failed()) {
                long uploadNanos = lw.update();
                Picture menu = menuPicture(mc, view, lw, wanted, now);
                if (menu != null) return menu;
                view.stats.frame("showing " + view.window, "Graphics Capture", lw.averageMillis());
                if (uploadNanos > 0) view.stats.uploaded(uploadNanos);
                int w = lw.frameWidth(), h = lw.frameHeight();
                return w == 0 ? null : new Picture(lw.renderType(), lw.textureId(), w, h, 0, 0, w, h, null);
            }
            DesktopScreens.LOG.info("Screens in the world: {} can't be captured by itself; showing its part of {} instead", view.window, wanted);
            view.cutOut = true;
            stop(view);
        }
        if (view.liveWindow != null) { // its window was closed: the monitor from now on
            view.liveWindow.release();
            view.liveWindow = null;
        }
        if (view.live == null || !view.live.monitor().sameAs(wanted)) {
            stop(view);
            view.live = LiveCapture.acquire(mc, wanted);
            DesktopScreens.LOG.info("Screens in the world: showing {}{}", view.window != null ? view.window + " on " : "", wanted);
        }
        LiveCapture live = view.live;
        long uploadNanos = live.update();
        view.stats.frame("showing " + live.monitor().label() + (view.window != null ? ", one window" : ""),
                live.method(), live.averageMillis());
        if (uploadNanos > 0) view.stats.uploaded(uploadNanos);
        int w = live.frameWidth(), h = live.frameHeight();
        if (w == 0) return null;
        int[] area = view.window != null ? view.window.rectOn(live.monitor()) : null; // null while it's minimized: the whole monitor
        if (area != null) {
            Monitor m = live.monitor();
            int x = Math.max(0, area[0] - m.x), y = Math.max(0, area[1] - m.y);
            int right = Math.min(w, area[2] - m.x), bottom = Math.min(h, area[3] - m.y);
            if (right > x && bottom > y) return new Picture(live.renderType(), live.textureId(), w, h, x, y, right - x, bottom - y, live.gameArea());
        }
        return new Picture(live.renderType(), live.textureId(), w, h, 0, 0, w, h, live.gameArea());
    }

    /**
     * While the fullscreen game shows a menu, Graphics Capture pauses (under it Windows sometimes doesn't draw the game's
     * cursor, {@link LiveWindow#graphicsCaptureAllowed}) and PrintWindow takes the window's pictures instead. Where that
     * doesn't work, the window's part of its monitor's picture shows meanwhile, live, so a video in picture-in-picture
     * goes on in the inventory (2026-09-26); whatever covers the window shows too then, as before screens had
     * their own capture. After the menu, until the window's own capture has a new picture (at most
     * {@link #RESUME_NANOS}), so it doesn't jump back to the picture from before. Null when the window's own picture is
     * the one to show, and while the monitor has no picture yet or the window is minimized (its last picture stays).
     */
    private static Picture menuPicture(Minecraft mc, View view, LiveWindow lw, Monitor wanted, long now) {
        // Usually the window's own pictures go on meanwhile anyway (PrintWindow, WindowCapture#picturesInMenus).
        boolean paused = !LiveWindow.graphicsCaptureAllowed(mc) && !lw.picturesInMenus();
        if (paused) {
            view.resumeFrom = -1;
        } else if (view.menuLive != null) {
            if (view.resumeFrom < 0) {
                view.resumeFrom = lw.capturedPictures();
                view.resumedAt = now;
            }
            if (lw.capturedPictures() > view.resumeFrom || now - view.resumedAt > RESUME_NANOS) {
                view.menuLive.release();
                view.menuLive = null;
            }
        }
        if (!paused && view.menuLive == null) return null;
        if (view.menuLive == null || !view.menuLive.monitor().sameAs(wanted)) {
            if (view.menuLive != null) view.menuLive.release();
            view.menuLive = LiveCapture.acquire(mc, wanted);
        }
        LiveCapture live = view.menuLive;
        long uploadNanos = live.update();
        view.stats.frame("showing " + view.window, live.method() + " (a menu is open)", live.averageMillis());
        if (uploadNanos > 0) view.stats.uploaded(uploadNanos);
        int w = live.frameWidth(), h = live.frameHeight();
        int[] area = view.window.rectOn(live.monitor());
        if (w == 0 || area == null) return null;
        Monitor m = live.monitor();
        int x = Math.max(0, area[0] - m.x), y = Math.max(0, area[1] - m.y);
        int right = Math.min(w, area[2] - m.x), bottom = Math.min(h, area[3] - m.y);
        return right > x && bottom > y ? new Picture(live.renderType(), live.textureId(), w, h, x, y, right - x, bottom - y, live.gameArea()) : null;
    }

    /**
     * Where the view's choice points now: its window, on whatever monitor it is (looked for again while it isn't
     * open), else its monitor, else the one picked automatically. Worked out again at most once a second.
     */
    private static Monitor source(Minecraft mc, View view) {
        long now = System.nanoTime();
        if (view.monitor != null && now - view.sourceCheckedAt < SOURCE_CHECK_NANOS) return view.monitor;
        view.sourceCheckedAt = now;
        if (view.window != null && !view.window.alive()) view.window = null;
        if (view.window == null && !view.windowKey.isEmpty()) view.window = AppWindow.find(view.windowKey, view.windowHandle);
        Monitor on = view.window != null ? Monitors.of(view.window.handle()) : null;
        if (on == null) on = Monitors.byName(view.monitorName);
        if (on == null) on = Monitors.defaultTarget(Monitors.of(GLFWNativeWin32.glfwGetWin32Window(mc.getWindow().getWindow())));
        view.monitor = on;
        return on;
    }

    /** A view asked for by sharing or the builder, not a screen: kept while asked for, but it captures nothing itself. */
    private static View asked(SourceChoice choice) {
        View view = view(choice);
        view.lastWanted = Math.max(view.lastWanted, System.nanoTime() - LINGER_NANOS / 2);
        return view;
    }

    /**
     * For sharing: the monitor a choice shows now, and its window (null for the whole monitor) with the window's frame
     * on that monitor (desktop pixels; null for all of it, also while the window is minimized).
     */
    record Shown(Monitor monitor, AppWindow window, int[] area) {}

    /** What screens showing {@code choice} show now, for sharing. Windows only. */
    static Shown shown(Minecraft mc, SourceChoice choice) {
        View view = asked(choice);
        Monitor monitor = source(mc, view);
        return new Shown(monitor, view.window, view.window != null ? view.window.rectOn(monitor) : null);
    }

    /** What screens show: one window, or a monitor by its short name (like "DISPLAY2"); its size in desktop pixels. */
    record Source(boolean window, String monitor, int width, int height) {}

    /** What {@code choice} shows now: its window, else the monitor. Null off Windows. */
    static Source currentSource(Minecraft mc, SourceChoice choice) {
        if (!Win32.SUPPORTED) return null;
        View view = asked(choice);
        Monitor monitor = source(mc, view);
        String name = monitor.name.replace("\\\\.\\", "");
        int[] area = view.window != null ? view.window.rectOn(monitor) : null; // null while it's minimized
        return area != null ? new Source(true, name, area[2] - area[0], area[3] - area[1])
                : new Source(false, name, monitor.width, monitor.height);
    }

    /** Time the renderer spent drawing a block of {@code group}, for the frame timing in the log. */
    static void drew(Minecraft mc, ScreenGroups.Group group, long nanos) {
        View view = VIEWS.get(SourceChoice.of(mc.level, group).key());
        if (view != null) view.stats.drew(nanos);
    }

    /**
     * Every client tick: a view stops capturing once no screen wanted its picture for a few seconds, and all do
     * without a world; views nothing asked for in that time are forgotten.
     */
    static void tick(Minecraft mc) {
        long now = System.nanoTime();
        for (Iterator<View> it = VIEWS.values().iterator(); it.hasNext(); ) {
            View view = it.next();
            if (mc.level == null || now - view.lastWanted > LINGER_NANOS) {
                stop(view);
                it.remove();
            }
        }
    }

    private static void stop(View view) {
        if (view.menuLive != null) {
            view.menuLive.release();
            view.menuLive = null;
            view.resumeFrom = -1;
        }
        if (view.liveWindow != null) {
            view.stats.flush();
            DesktopScreens.LOG.info("Screens in the world: stopped showing {}", view.window);
            view.liveWindow.release();
            view.liveWindow = null;
            view.picture = null;
        }
        if (view.live == null) return;
        view.stats.flush();
        DesktopScreens.LOG.info("Screens in the world: stopped showing {}", view.live.monitor());
        view.live.release();
        view.live = null;
        view.picture = null;
    }
}
