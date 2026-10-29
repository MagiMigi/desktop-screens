package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.core.BrowserOcclusion;
import com.desktopscreens.core.DesktopCapture;
import com.desktopscreens.core.Monitor;
import com.desktopscreens.core.Monitors;
import com.desktopscreens.core.Win32;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentTranslation;

import java.util.HashMap;
import java.util.Map;

/**
 * One monitor's capture and its texture, shared by everything that shows that monitor: the desktop view and the
 * screens in the world. Windows allows one Desktop Duplication per monitor per program, and one upload per new
 * picture is enough for all of them. Render thread only.
 */
final class LiveCapture {
    private static final Map<String, LiveCapture> OPEN = new HashMap<>();
    /** How many open captures hid the game window from screen capture; it's shown again when the last one closes. */
    private static int hidingGame;
    /** The note about streams was shown in this world already. */
    private static boolean noted;

    private final Minecraft mc;
    private final Monitor monitor;
    private final boolean gameMonitor;
    private final DesktopTexture texture = new DesktopTexture();
    /** This capture hid the game from screen capture (and counts in {@link #hidingGame}). */
    private boolean hidGame, warnedHide;
    /** Replaced when fast capture is switched on or off; read by the sharing encoder's thread too. */
    private volatile DesktopCapture capture;
    private int users;
    private boolean pausedForView;
    private int frameWidth, frameHeight;
    /** How the game window stood to this monitor last frame (see {@link #allowDuplication}), for the log; -1 before the first. */
    private int gameState = -1;

    private LiveCapture(Minecraft mc, Monitor monitor) {
        this.mc = mc;
        this.monitor = monitor;
        gameMonitor = monitor.sameAs(Monitors.of(GameWindow.hwnd()));
        DesktopConfig.load();
        hideGameIfOnIt();
        capture = newCapture();
    }

    /**
     * If the player chose it ({@link DesktopConfig#hideGame}, off by default), hides the game from screen capture while
     * it's on this monitor, so its picture shows what's behind the game instead of an endless mirror of the game
     * inside itself; only then, since it also blanks the game in OBS and screen sharing (Windows has no "hidden from
     * this program only"). Checked every frame and tick, not just when the capture starts. Also sets it again if
     * Windows dropped it.
     */
    private void hideGameIfOnIt() {
        long gameWindow = GameWindow.hwnd(); // a new one after F11, which hasn't been hidden yet
        if (!DesktopConfig.hideGame) {
            if (hidGame) {
                hidGame = false;
                if (--hidingGame == 0) showGame("\"Hide the game\" is off");
            }
            return;
        }
        if (hidGame) {
            if (!Win32.excludedFromCapture(gameWindow) && Win32.setExcludedFromCapture(gameWindow, true)) {
                DesktopScreens.LOG.info("Hid the game from screen capture on {} again: Windows had dropped it ({})", monitor.label(),
                        Win32.windowState(gameWindow));
            }
            return;
        }
        if (!Win32.overlaps(gameWindow, monitor)) return;
        hidGame = Win32.setExcludedFromCapture(gameWindow, true);
        if (hidGame) {
            if (hidingGame++ == 0) {
                DesktopScreens.LOG.info("Hid the game from screen capture, so Discord and OBS see what's behind it: {} is shown, which the game is on ({})",
                        monitor.label(), Win32.windowState(gameWindow));
                noteHidden();
            }
        } else if (!warnedHide) {
            warnedHide = true;
            DesktopScreens.LOG.warn("Couldn't hide the game from screen capture on {}: it may show inside itself", monitor);
        }
    }

    /**
     * Once per world, when the game starts hiding: why Discord and OBS lose it, and where that's changed. In the chat:
     * 1.12.2's notes (toasts) hold one short line.
     */
    private void noteHidden() {
        if (noted || mc.world == null) return;
        noted = true;
        mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("desktopscreens.stream.note.title"));
        mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("desktopscreens.stream.note"));
        DesktopScreens.LOG.info("Said so in the chat (once per world)");
    }

    /**
     * Where the game itself shows in this picture, {left, top, right, bottom} in the picture's pixels; null while it
     * doesn't (hidden from capture, minimized, on another monitor).
     */
    int[] gameArea() {
        if (hidGame) return null;
        int[] r = Win32.rectOn(GameWindow.hwnd(), monitor);
        return r == null ? null : new int[] {r[0] - monitor.x, r[1] - monitor.y, r[2] - monitor.x, r[3] - monitor.y};
    }

    /** The last capture that hid the game stopped hiding it. */
    private void showGame(String why) {
        long gameWindow = GameWindow.hwnd();
        Win32.setExcludedFromCapture(gameWindow, false);
        DesktopScreens.LOG.info("Showed the game to screen capture again: {} ({})", why, Win32.windowState(gameWindow));
    }

    /** Out of any world: the next one says it again. */
    static void worldLeft() {
        noted = false;
    }

    /** The capture of {@code monitor}, started if nothing shows it yet. Give it back with {@link #release}. */
    static LiveCapture acquire(Minecraft mc, Monitor monitor) {
        LiveCapture live = OPEN.get(monitor.name);
        if (live == null) {
            live = new LiveCapture(mc, monitor);
            OPEN.put(monitor.name, live);
        }
        live.users++;
        live.updatePause();
        return live;
    }

    /** Stops the capture once the last user gives it back. */
    void release() {
        if (--users > 0) {
            updatePause();
            return;
        }
        OPEN.remove(monitor.name);
        capture.close();
        texture.delete();
        if (hidGame && --hidingGame == 0) showGame("nothing shows " + monitor.label() + " any more");
        checkGameWindow(GameWindow.hwnd());
    }

    /** Hiding from capture is a window setting; set it again after changing the window's styles, in case that reset it. */
    static void hideGameAgain(long gameWindow) {
        if (hidingGame > 0 && !Win32.setExcludedFromCapture(gameWindow, true)) {
            DesktopScreens.LOG.warn("Couldn't hide the game from screen capture again: {}", Win32.windowState(gameWindow));
        }
    }

    /**
     * While the game's own monitor or any window ({@link LiveWindow}) is captured, browsers behind a windowed game must
     * keep drawing, or a video there freezes in the picture ({@link BrowserOcclusion}). Every frame, when the last
     * capture closes, and after the in-place view (which changes the same window styles).
     */
    static void checkGameWindow(long gameWindow) {
        if (BrowserOcclusion.keep(gameWindow, hidingGame > 0 || LiveWindow.anyOpen())) hideGameAgain(gameWindow);
    }

    Monitor monitor() {
        return monitor;
    }

    DesktopTexture texture() {
        return texture;
    }

    /** The newest picture's size, 0 until the first one arrived. */
    int frameWidth() {
        return frameWidth;
    }

    int frameHeight() {
        return frameHeight;
    }

    /**
     * Uploads the newest picture, if there's one nobody uploaded yet. Call before drawing, every frame. Returns how long
     * the upload took, or 0.
     */
    long update() {
        // Checked every frame, since F11 and click-through (which makes the window 1 px short) change it.
        allowDuplication(capture);
        long start = System.nanoTime();
        return capture.consumeLatest(this::upload) ? System.nanoTime() - start : 0;
    }

    /** For sharing, which reads the pictures on its own thread ({@link DesktopCapture#readLatest}). */
    DesktopCapture capture() {
        return capture;
    }

    /** For a user that doesn't draw (sharing): keeps the choice between Desktop Duplication and GDI current. Render thread. */
    void refresh() {
        allowDuplication(capture);
    }

    /**
     * Every client tick, for all open captures: the choices below also while nothing draws them (a screen out of
     * sight keeps its capture for a few seconds, so turning around doesn't restart it).
     */
    static void tickAll() {
        for (LiveCapture live : OPEN.values()) live.allowDuplication(live.capture);
    }

    private void upload(long bgraAddress, int width, int height) {
        texture.upload(bgraAddress, width, height);
        frameWidth = width;
        frameHeight = height;
    }

    /**
     * The desktop view doesn't need pictures while the real screen shows through the game window (click-through).
     * The capture only pauses if nothing else shows this monitor.
     */
    void setPausedForView(boolean paused) {
        pausedForView = paused;
        updatePause();
    }

    private void updatePause() {
        capture.setPaused(pausedForView && users <= 1);
    }

    /** Same monitor, fresh capture: after switching between fast capture and GDI. The last picture stays up meanwhile. */
    void restart() {
        capture.close();
        capture = newCapture();
        updatePause();
    }

    private DesktopCapture newCapture() {
        DesktopCapture c = new DesktopCapture(monitor, DesktopConfig.fastCapture, message -> DesktopScreens.LOG.info("Capture: {}", message));
        // On the game's own monitor the real pointer is always in sight, so a captured copy would only trail behind it.
        c.setDrawCursor(!gameMonitor);
        // Right away, before the capture thread's first frame (a GDI grab) is done and it would start Desktop Duplication.
        allowDuplication(c);
        return c;
    }

    /**
     * Desktop Duplication only while the game doesn't fill this monitor: under a fullscreen game, Windows keeps
     * interrupting it. Minecraft's own fullscreen setting counts too, not only the window's size. Every change is
     * logged with the window's state.
     */
    private void allowDuplication(DesktopCapture c) {
        long gameWindow = GameWindow.hwnd();
        hideGameIfOnIt();
        checkGameWindow(gameWindow);
        boolean fullscreenHere = mc.isFullScreen() && Win32.overlaps(gameWindow, monitor);
        boolean covers = Win32.coversMonitor(gameWindow, monitor);
        c.setDuplicationAllowed(!fullscreenHere && !covers);
        // Under Graphics Capture of the fullscreen game's monitor, the game's cursor wasn't drawn on the newer versions:
        // not while a menu shows it (the desktop view itself moves the real pointer on the desktop, so it doesn't count).
        c.setGraphicsCaptureAllowed(mc.currentScreen == null
                || mc.currentScreen instanceof DesktopScreen && ((DesktopScreen) mc.currentScreen).usingDesktop());
        int state = (fullscreenHere ? 1 : 0) | (covers ? 2 : 0);
        if (state == gameState) return;
        gameState = state;
        DesktopScreens.LOG.info("Capture of {}: the game is {}fullscreen here and {} the monitor exactly, so {} (game window {})",
                monitor.label(), fullscreenHere ? "" : "not ", covers ? "covers" : "doesn't cover",
                fullscreenHere || covers ? "Graphics Capture (or GDI)" : "Desktop Duplication allowed", Win32.windowState(gameWindow));
    }

    /** How the pictures come right now: "DXGI", "Graphics Capture" or "GDI". */
    String method() {
        return capture.method();
    }

    double averageMillis() {
        return capture.averageMillis();
    }

    /** The last capture error, or null while capturing works. */
    String error() {
        return capture.error();
    }

    /** True while protected video (DRM) shows black in the picture, which Windows does in every screen capture. */
    boolean protectedContentHidden() {
        return capture.protectedContentHidden();
    }
}
