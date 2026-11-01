package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.core.AppWindow;
import com.desktopscreens.core.DesktopInput;
import com.desktopscreens.core.Monitor;
import com.desktopscreens.core.Monitors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiPageButtonList;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Shows the desktop over the game window, in one of two modes:
 * <ul>
 *   <li>DESKTOP: the desktop fills the window (or leaves a margin of world around it, when framed), and the real
 *       mouse and keyboard control that monitor. On Minecraft's own monitor, clicks go through the game window
 *       to the real screen behind it. Host key commands arrive through {@link #onHostCombo} and
 *       {@link #onHostWheel}.</li>
 *   <li>SETTINGS: Minecraft has the mouse and keyboard back, with a settings panel on the left and a live
 *       preview of the desktop on the right.</li>
 * </ul>
 */
final class DesktopScreen extends GuiScreen {
    /** How long the banner at the top shows its hints, or a short message; then it fades out over FADE_MILLIS. */
    private static final long HINT_MILLIS = 6000, FLASH_MILLIS = 2500, FADE_MILLIS = 500;
    private static final int PANEL_WIDTH = 172, PAD = 8, PREVIEW_MARGIN = 8;
    private static final int[] PALETTE = {
            0xFFFFFF, 0xAAAAAA, 0x808080, 0x404040, 0xFF4D4D, 0xFF9F40,
            0xFFE14D, 0x4CDB6B, 0x3FD0E0, 0x4A7BFF, 0xA45BFF, 0xFF6AD5};

    /**
     * SHARING is a page of the settings: whom a screen in the world is shared with (only when opened from one). PIP is
     * picture-in-picture's page, STREAM whether Discord and OBS may lose the game.
     */
    private enum Mode { DESKTOP, SETTINGS, SHARING, PIP, STREAM }

    private final DesktopSession session;
    private final GuiScreen parent; // where closing goes; null = back to the game
    /** The screen in the world this was opened on with G, or null (the tablet, the settings). */
    private final ScreenGroups.Group screen;
    private Mode mode;
    private boolean started;
    /** The camera is on its way into the screen or back out; nothing is drawn over the world meanwhile. */
    private boolean gliding, leaving;
    /** The camera is in front of the screen (glided in), not at the player's eyes. */
    private boolean atScreen;
    private boolean hidGui, hideGuiBefore;
    private boolean cameFromDesktop; // Esc in the settings goes back to the desktop instead of closing
    private long hintUntil;
    private ITextComponent flash;
    private long flashUntil;

    // Settings widgets, only while in SETTINGS mode.
    /** Whom a screen in the world is shared with: only when opened from one. */
    private SharingPage sharingPage;
    private Widget.Slider thicknessSlider, rainbowSlider;
    /** Picture-in-picture's corner, which dragging the picture in the preview changes. */
    private Widget.Cycle<PictureInPicture.Corner> pipCornerButton;
    private GuiTextField hexBox;
    private final List<Widget.Swatch> swatches = new ArrayList<>();
    private int colorLabelY;
    // The text below the buttons of the picture-in-picture and streaming pages.
    private List<String> pageText = Collections.emptyList();
    private int pageTextY;

    private DesktopScreen(DesktopSession session, GuiScreen parent, Mode mode, ScreenGroups.Group screen) {
        this.session = session;
        this.parent = parent;
        this.mode = mode;
        this.screen = screen;
    }

    /** Opens straight into using the desktop. */
    static DesktopScreen desktop(DesktopSession session, GuiScreen parent) {
        return new DesktopScreen(session, parent, Mode.DESKTOP, null);
    }

    /** Opens on the settings panel, with a live preview. */
    static DesktopScreen settings(DesktopSession session, GuiScreen parent) {
        return new DesktopScreen(session, parent, Mode.SETTINGS, null);
    }

    /** Uses a screen in the world that's on for this player. */
    static DesktopScreen onScreen(DesktopSession session, ScreenGroups.Group screen) {
        return new DesktopScreen(session, null, Mode.DESKTOP, screen);
    }

    /** Whether a screen in the world is being used, with the HUD hidden for it (see {@link #initGui}). */
    static boolean hidesHud(Minecraft mc) {
        return mc.currentScreen instanceof DesktopScreen && ((DesktopScreen) mc.currentScreen).hidGui;
    }

    /** Also again on every resize, with the widgets cleared. */
    @Override
    public void initGui() {
        if (!started) {
            started = true;
            // Covers the settings too: while any desktop view is open, the game must not minimize or pause itself
            // just because you clicked something on another monitor.
            FocusHandOff.begin(mc);
            if (screen != null) {
                hideGuiBefore = mc.gameSettings.hideGUI;
                mc.gameSettings.hideGUI = true;
                hidGui = true;
            }
            if (mode == Mode.DESKTOP) enterDesktop();
        }
        addWidgets();
    }

    /**
     * Into the desktop. With a screen in the world, how depends on the monitor used, so this runs again whenever
     * that changes: Minecraft's own can only be used in place (clicks land where the real windows are), so the camera
     * glides in first; another one is used right where you stand, and a camera that glided in goes back.
     */
    private void enterDesktop() {
        if (screen == null) {
            beginDesktop();
        } else if (session.isGameMonitor(session.target())) {
            session.setInWorld(false);
            if (atScreen) {
                beginDesktop();
            } else {
                int[] size = session.pictureSize();
                gliding = true;
                CameraGlide.in(CameraGlide.facing(screen, screen.pictureRect(size[0], size[1]), mc));
            }
        } else {
            if (atScreen) {
                CameraGlide.out();
                atScreen = false;
            }
            session.setInWorld(true);
            beginDesktop();
        }
    }

    private void addWidgets() {
        hexBox = null;
        swatches.clear();
        pageText = Collections.emptyList();
        Keyboard.enableRepeatEvents(mode != Mode.DESKTOP); // holding a key down in the hex box
        if (mode == Mode.SETTINGS) addSettingsWidgets();
        else if (mode == Mode.STREAM) addStreamWidgets();
        else if (mode == Mode.PIP) addPipWidgets();
        else if (mode == Mode.SHARING) sharingPage.addWidgets(buttonList::add, fontRenderer, PAD, PANEL_WIDTH - 2 * PAD, height);
    }

    /** The text box of the page shown, if it has one: the hex color, or the sharing page's name. */
    private GuiTextField textBox() {
        return mode == Mode.SHARING ? sharingPage.nameBox() : hexBox;
    }

    private void rebuildWidgets() {
        buttonList.clear();
        addWidgets();
    }

    private void beginDesktop() {
        session.startInput(); // only works once the screen is open: that's when Minecraft lets go of the mouse
        hintUntil = Minecraft.getSystemTime() + HINT_MILLIS;
    }

    private void switchTo(Mode newMode) {
        if (newMode == mode) return;
        // The streaming page and back doesn't change where Esc in the settings goes.
        if (newMode == Mode.DESKTOP || mode == Mode.DESKTOP) cameFromDesktop = mode == Mode.DESKTOP;
        if (newMode == Mode.SHARING && sharingPage == null) sharingPage = new SharingPage(mc, screen, () -> switchTo(Mode.SETTINGS));
        mode = newMode;
        if (newMode == Mode.DESKTOP) enterDesktop();
        else session.stopInput();
        DesktopConfig.save();
        rebuildWidgets();
    }

    /** A key was pressed while the host key was held. Game thread. */
    void onHostCombo(int virtualKey) {
        if (mode != Mode.DESKTOP) return;
        if (session.inWorld()) {
            // The screen in the world shows the picture: scaling, framing and zoom don't apply. The next monitor may
            // be Minecraft's own, which setMonitor handles by gliding in.
            switch (virtualKey) {
                case DesktopInput.VK_O: switchTo(Mode.SETTINGS); break;
                case DesktopInput.VK_W: toggleWindow(); break;
                case DesktopInput.VK_M: cycleMonitor(); break;
                case DesktopInput.VK_P: pinPicture(); break;
                default: showFlash(new TextComponentTranslation("desktopscreens.hint.not_in_world", DesktopClient.hostKey())); break;
            }
            return;
        }
        switch (virtualKey) {
            case DesktopInput.VK_S: cycleScaling(); break;
            case DesktopInput.VK_M: cycleMonitor(); break;
            case DesktopInput.VK_O: switchTo(Mode.SETTINGS); break;
            case DesktopInput.VK_F: toggleFramed(); break;
            case DesktopInput.VK_W: toggleWindow(); break;
            case DesktopInput.VK_P: pinPicture(); break;
            case DesktopInput.VK_UP: case DesktopInput.VK_OEM_PLUS: case DesktopInput.VK_ADD: zoom(1); break;
            case DesktopInput.VK_DOWN: case DesktopInput.VK_OEM_MINUS: case DesktopInput.VK_SUBTRACT: zoom(-1); break;
            case DesktopInput.VK_0: case DesktopInput.VK_NUMPAD0: zoom(0); break;
            default: break;
        }
    }

    /**
     * You switched to Minecraft yourself (Alt+Tab, its taskbar button): back to the game, as the host key does. The way
     * back for keyboards without the host key. Game thread.
     */
    void onGameChosen() {
        if (mode != Mode.DESKTOP || !session.controlling()) return; // the settings took the mouse and keyboard back meanwhile
        DesktopScreens.LOG.info("Back to the game: Minecraft was switched to (Alt+Tab or its taskbar button)");
        close();
    }

    /** The mouse wheel turned while the host key was held. Game thread. */
    void onHostWheel(int delta) {
        if (mode != Mode.DESKTOP || session.inWorld()) return;
        int notches = session.wheelNotches(delta);
        if (notches != 0) zoom(notches);
    }

    /** Zooms in or out by whole steps; 0 goes back to 1x. */
    private void zoom(int steps) {
        if (!session.canZoom()) {
            showFlash(new TextComponentTranslation("desktopscreens.hint.zoom_in_place"));
            return;
        }
        if (steps == 0) session.resetZoom();
        else session.zoomBy(steps);
        showFlash(new TextComponentTranslation(session.zoomed() ? "desktopscreens.hint.zoom_reset" : "desktopscreens.hint.zoom",
                session.zoomLabel(), DesktopClient.hostKey("0")));
    }

    /** Right Ctrl + F: between fullscreen and framed, with the margin from the settings. */
    private void toggleFramed() {
        DesktopConfig.framed = !DesktopConfig.framed;
        DesktopConfig.save();
        showFlash(new TextComponentTranslation(session.clickingThrough() ? "desktopscreens.hint.view_in_place" : "desktopscreens.hint.view",
                viewLabel(DesktopConfig.framed ? DesktopConfig.frameMargin : 0)));
    }

    /** Right Ctrl + W: just the window under the mouse, or the whole monitor again. */
    private void toggleWindow() {
        if (session.window() != null) {
            session.setWindow(null);
            showFlash(new TextComponentTranslation("desktopscreens.hint.whole_monitor", monitorLabel(session.target())));
        } else {
            AppWindow w = session.windowUnderPointer();
            if (w == null) {
                showFlash(new TextComponentTranslation("desktopscreens.hint.no_window", DesktopClient.hostKey("W")));
                return;
            }
            session.setWindow(w);
            showFlash(new TextComponentTranslation("desktopscreens.hint.one_window", shorten(w.label()), DesktopClient.hostKey("W")));
        }
        DesktopConfig.save();
    }

    /**
     * Right Ctrl + P: the window under the mouse, or over the empty desktop this whole monitor, as picture-in-picture
     * (it shows once you're back in the game); over the one it shows already, off again. Without a Tablet it pins too,
     * and says it shows once you carry one.
     */
    private void pinPicture() {
        AppWindow w = session.windowUnderPointer();
        Monitor on = w != null ? Monitors.of(w.handle()) : null;
        Monitor monitor = on != null ? on : session.target();
        if (PictureInPicture.pinOrHide(w, monitor)) {
            Object what = w != null ? new TextComponentString(shorten(w.label())) : monitorLabel(monitor);
            showFlash(DesktopClient.hasTablet(mc) ? new TextComponentTranslation("desktopscreens.pip.on", what, DesktopClient.hostKey("P"))
                    : new TextComponentTranslation("desktopscreens.pip.pinned_no_tablet", what));
        } else {
            showFlash(new TextComponentTranslation("desktopscreens.pip.off"));
        }
    }

    /** Window titles can be long (a video's, say). */
    private static String shorten(String text) {
        return text.length() <= 48 ? text : text.substring(0, 45) + "...";
    }

    /** "Fullscreen" for 0, otherwise "Framed, 10% margin". */
    private static ITextComponent viewLabel(int margin) {
        return margin == 0 ? new TextComponentTranslation("desktopscreens.view.fullscreen")
                : new TextComponentTranslation("desktopscreens.view.framed", margin);
    }

    private void cycleScaling() {
        DesktopConfig.scaling = DesktopConfig.scaling.next();
        DesktopConfig.save();
        showFlash(new TextComponentTranslation(session.clickingThrough() ? "desktopscreens.hint.scaling_in_place" : "desktopscreens.hint.scaling",
                DesktopConfig.scaling.label()));
    }

    /** Moves to the next monitor and takes control of it. */
    private void cycleMonitor() {
        List<Monitor> monitors = Monitors.list();
        if (monitors.size() < 2) {
            showFlash(new TextComponentTranslation("desktopscreens.hint.one_monitor"));
            return;
        }
        int index = 0;
        for (int i = 0; i < monitors.size(); i++) {
            if (monitors.get(i).sameAs(session.target())) index = i;
        }
        setMonitor(monitors.get((index + 1) % monitors.size()));
        showFlash(new TextComponentTranslation("desktopscreens.hint.monitor", monitorLabel(session.target())));
        // The hint differs on Minecraft's own monitor (clicks go through the game there), so show it again after the flash.
        hintUntil = Minecraft.getSystemTime() + HINT_MILLIS;
    }

    /** The whole of this monitor (leaving the one window, if one was shown). */
    private void setMonitor(Monitor monitor) {
        // On a screen in the world, Minecraft's own monitor is used in place and others through the screen, so stop
        // first (or setTarget would take control of the new one straight away) and enter again the way it needs.
        boolean reenter = screen != null && mode == Mode.DESKTOP;
        if (reenter) session.stopInput();
        if (session.window() != null) session.setWindow(null);
        session.setTarget(monitor);
        session.choice().monitor = monitor.name;
        session.choice().changed();
        DesktopConfig.save();
        if (reenter) enterDesktop();
    }

    /** The monitor Minecraft is on is marked: there your clicks go through the game window. */
    private ITextComponent monitorLabel(Monitor monitor) {
        return session.isGameMonitor(monitor)
                ? new TextComponentTranslation("desktopscreens.settings.monitor_game", monitor.label())
                : new TextComponentString(monitor.label());
    }

    /** An entry in the settings' source button: a whole monitor, or the one window shown (only while there is one). */
    private static final class Source {
        final Monitor monitor;
        final AppWindow window;

        Source(Monitor monitor, AppWindow window) {
            this.monitor = monitor;
            this.window = window;
        }
    }

    /** The longest label that fits the button. */
    private ITextComponent sourceLabel(Source source) {
        int room = PANEL_WIDTH - 2 * PAD - 4; // the button, less the margin its text keeps on each side
        if (source.window != null) {
            ITextComponent full = new TextComponentTranslation("desktopscreens.settings.window", shorten(source.window.label()));
            if (fontRenderer.getStringWidth(full.getFormattedText()) <= room) return full;
            return new TextComponentString(fontRenderer.trimStringToWidth(source.window.label(), room - fontRenderer.getStringWidth("...")) + "...");
        }
        Monitor m = source.monitor;
        ITextComponent label = monitorLabel(m);
        ITextComponent shortest = session.isGameMonitor(m)
                ? new TextComponentTranslation("desktopscreens.settings.monitor_game", m.shortLabel()) : new TextComponentString(m.shortLabel());
        for (ITextComponent c : new ITextComponent[] {new TextComponentTranslation("desktopscreens.settings.monitor_value", label), label}) {
            if (fontRenderer.getStringWidth(c.getFormattedText()) <= room) return c;
        }
        return shortest;
    }

    private void showFlash(ITextComponent text) {
        flash = text;
        flashUntil = Minecraft.getSystemTime() + FLASH_MILLIS;
    }

    private <T extends GuiButton> T add(T widget) {
        buttonList.add(widget);
        return widget;
    }

    private void addSettingsWidgets() {
        int x = PAD, w = PANEL_WIDTH - 2 * PAD, y = 20;
        List<Monitor> monitors = Monitors.list(); // read fresh, in case one was plugged in or unplugged
        Monitor current = null;
        for (Monitor m : monitors) {
            if (m.sameAs(session.target())) current = m;
        }
        if (current == null) { // the monitor we were showing is gone
            current = monitors.get(0);
            setMonitor(current);
        }
        // The monitors, and first the one window shown, if there is one. Windows are picked with Right Ctrl + W.
        List<Source> sources = new ArrayList<>();
        Source initial = null;
        if (session.window() != null) sources.add(initial = new Source(current, session.window()));
        for (Monitor m : monitors) {
            Source s = new Source(m, null);
            sources.add(s);
            if (initial == null && m.sameAs(current)) initial = s;
        }
        add(new Widget.Cycle<>(x, y, w, null, () -> sources, this::sourceLabel, initial, value -> {
            if (value.window == null) {
                setMonitor(value.monitor);
            } else if (value.window.alive()) {
                session.setWindow(value.window);
                DesktopConfig.save();
            }
        })).tooltip(new TextComponentTranslation("desktopscreens.settings.source.tooltip", DesktopClient.hostKeyName()));
        y += 22;
        add(new Widget.Cycle<>(x, y, w, new TextComponentTranslation("desktopscreens.settings.scaling"), () -> Arrays.asList(Scaling.values()),
                Scaling::label, DesktopConfig.scaling, value -> DesktopConfig.scaling = value));
        y += 22;
        // One slider for both, to save a row: all the way left is fullscreen, anything else is framed with that margin.
        add(new Widget.Slider(x, y, w, value -> new TextComponentTranslation("desktopscreens.settings.view", viewLabel(value)),
                0, DesktopConfig.MAX_MARGIN, DesktopConfig.framed ? DesktopConfig.frameMargin : 0, value -> {
                    DesktopConfig.framed = value > 0;
                    if (value > 0) DesktopConfig.frameMargin = value;
                })).tooltip(new TextComponentTranslation("desktopscreens.settings.view.tooltip", DesktopClient.hostKeyName()));
        y += 22;
        // The streaming page shares this row: there's no room for another.
        int streamWidth = 50;
        add(new Widget.Cycle<>(x, y, w - streamWidth - 2, new TextComponentTranslation("desktopscreens.settings.fast_capture"),
                () -> Arrays.asList(true, false), DesktopScreen::onOff, DesktopConfig.fastCapture, value -> {
                    DesktopConfig.fastCapture = value;
                    session.restartCapture();
                })).tooltip(new TextComponentTranslation("desktopscreens.settings.fast_capture.tooltip"));
        add(new Widget.Button(x + w - streamWidth, y, streamWidth, new TextComponentTranslation("desktopscreens.settings.stream"),
                () -> switchTo(Mode.STREAM))).tooltip(new TextComponentTranslation("desktopscreens.settings.stream.tooltip"));
        y += 22;
        add(new Widget.Cycle<>(x, y, w, new TextComponentTranslation("desktopscreens.settings.border"), () -> Arrays.asList(BorderStyle.values()),
                BorderStyle::label, DesktopConfig.borderStyle, value -> {
                    DesktopConfig.borderStyle = value;
                    updateActive();
                }));
        y += 22;
        thicknessSlider = add(new Widget.Slider(x, y, w, value -> new TextComponentTranslation("desktopscreens.settings.thickness", value),
                DesktopConfig.MIN_THICKNESS, DesktopConfig.MAX_THICKNESS, DesktopConfig.borderThickness, value -> DesktopConfig.borderThickness = value));
        y += 22;

        // The label, with the hex box on the same row, then the swatches in one row: the panel must still fit a
        // maximized 1080p window at GUI scale 4 (about 254 units tall).
        colorLabelY = y + 4;
        int hexWidth = 54;
        hexBox = new GuiTextField(0, fontRenderer, x + w - hexWidth + 1, y, hexWidth - 2, 16);
        hexBox.setValidator(text -> text != null && text.matches("#?[0-9a-fA-F]{0,6}"));
        hexBox.setMaxStringLength(7);
        hexBox.setText(DesktopConfig.hex(DesktopConfig.borderColor));
        hexBox.setGuiResponder(new GuiPageButtonList.GuiResponder() {
            @Override
            public void setEntryValue(int id, boolean value) {}

            @Override
            public void setEntryValue(int id, float value) {}

            @Override
            public void setEntryValue(int id, String value) {
                DesktopConfig.borderColor = DesktopConfig.color(value, DesktopConfig.borderColor);
            }
        });
        y += 20;
        int gap = 2, size = (w - (PALETTE.length - 1) * gap) / PALETTE.length;
        for (int i = 0; i < PALETTE.length; i++) {
            swatches.add(add(new Widget.Swatch(x + i * (size + gap), y, size, PALETTE[i], () -> DesktopConfig.borderColor, this::pickColor)));
        }
        y += size + 4;
        rainbowSlider = add(new Widget.Slider(x, y, w, value -> new TextComponentTranslation("desktopscreens.settings.rainbow_speed", value),
                DesktopConfig.MIN_RAINBOW_SECONDS, DesktopConfig.MAX_RAINBOW_SECONDS, DesktopConfig.rainbowSeconds, value -> DesktopConfig.rainbowSeconds = value));
        y += 22;

        // Picture-in-picture's page shares this row: a narrow button, so "Use the desktop" keeps room for its words.
        int pipWidth = 50;
        Widget use = add(new Widget.Button(x, y, w - pipWidth - 2, new TextComponentTranslation("desktopscreens.settings.use_desktop"),
                () -> switchTo(Mode.DESKTOP)));
        add(new Widget.Button(x + w - pipWidth, y, pipWidth, new TextComponentTranslation("desktopscreens.settings.pip"), () -> switchTo(Mode.PIP)))
                .tooltip(new TextComponentTranslation("desktopscreens.settings.pip.tooltip"));
        // Like G: back to where the settings were opened from, or with a tablet on you (not from the mods list alone).
        if (!cameFromDesktop && screen == null && !DesktopClient.hasTablet(mc)) {
            use.enabled = false;
            use.tooltip(new TextComponentTranslation("desktopscreens.settings.use_desktop.needs_tablet"));
        }
        y += 22;
        if (screen != null) {
            // Opened from a screen in the world (yours, or G wouldn't use it): sharing it shares this row with Done.
            int half = (w - 2) / 2;
            add(new Widget.Button(x, y, half, new TextComponentTranslation("desktopscreens.settings.sharing", SharingPage.count(mc, screen)),
                    () -> switchTo(Mode.SHARING))).tooltip(new TextComponentTranslation("desktopscreens.settings.sharing.tooltip"));
            add(new Widget.Button(x + w - half, y, half, new TextComponentTranslation("gui.done"), this::close));
        } else {
            add(new Widget.Button(x, y, w, new TextComponentTranslation("gui.done"), this::close));
        }
        updateActive();
    }

    /**
     * Picture-in-picture's page: shown or not, the corner, its size and the gap to the edges, whether the hotbar goes
     * over a big picture or below it, what's pinned and how to pin something else, and Back. The preview on the right shows it in a small game window.
     */
    private void addPipWidgets() {
        int x = PAD, w = PANEL_WIDTH - 2 * PAD, y = 20;
        DesktopConfig.load();
        Widget.Cycle<Boolean> shown = add(new Widget.Cycle<>(x, y, w, new TextComponentTranslation("desktopscreens.pip.shown"),
                () -> Arrays.asList(true, false), DesktopScreen::onOff, PictureInPicture.shown(), PictureInPicture::setShown));
        if (DesktopConfig.pip == null) {
            shown.enabled = false;
            shown.tooltip(new TextComponentTranslation("desktopscreens.pip.nothing", DesktopClient.hostKey("P")));
        }
        y += 22;
        // Its choices are asked for on every click: the dragged spot joins the corners once there is one.
        pipCornerButton = add(new Widget.Cycle<>(x, y, w, new TextComponentTranslation("desktopscreens.pip.corner"),
                PictureInPicture.Corner::choices, PictureInPicture.Corner::label, DesktopConfig.pipCorner, value -> DesktopConfig.pipCorner = value));
        PictureInPicture.resetPreview();
        y += 22;
        // Size and the gap share a row, so the text below still fits a maximized window at GUI scale 4.
        int half = (w - 2) / 2;
        add(new Widget.Slider(x, y, half, value -> new TextComponentTranslation("desktopscreens.pip.size", value),
                DesktopConfig.MIN_PIP_SIZE, DesktopConfig.MAX_PIP_SIZE, DesktopConfig.pipSize, value -> DesktopConfig.pipSize = value))
                .tooltip(new TextComponentTranslation("desktopscreens.pip.size.tooltip"));
        add(new Widget.Slider(x + w - half, y, half, value -> new TextComponentTranslation("desktopscreens.pip.gap", value),
                DesktopConfig.MIN_PIP_GAP, DesktopConfig.MAX_PIP_GAP, DesktopConfig.pipGap, value -> DesktopConfig.pipGap = value))
                .tooltip(new TextComponentTranslation("desktopscreens.pip.gap.tooltip"));
        y += 22;
        add(new Widget.Cycle<>(x, y, w, new TextComponentTranslation("desktopscreens.pip.hotbar"), () -> Arrays.asList(false, true),
                value -> new TextComponentTranslation(value ? "desktopscreens.pip.hotbar.below" : "desktopscreens.pip.hotbar.over"),
                DesktopConfig.pipClearOfHotbar, value -> DesktopConfig.pipClearOfHotbar = value))
                .tooltip(new TextComponentTranslation("desktopscreens.pip.hotbar.tooltip"));
        y += 28;
        // What's pinned (a window by its title, found once here: that walks every window), how to pin, and its key.
        SourceChoice pinned = DesktopConfig.pip;
        ITextComponent what;
        if (pinned == null) {
            what = new TextComponentTranslation("desktopscreens.pip.pinned_none");
        } else if (!pinned.window.isEmpty()) {
            AppWindow window = AppWindow.find(pinned.window, pinned.handle);
            what = new TextComponentTranslation("desktopscreens.pip.pinned", window != null ? shorten(window.label())
                    : new TextComponentTranslation("desktopscreens.pip.pinned_closed"));
        } else {
            Monitor monitor = Monitors.byName(pinned.monitor);
            what = new TextComponentTranslation("desktopscreens.pip.pinned", monitor != null ? monitorLabel(monitor) : pinned.monitor);
        }
        ITextComponent key = DesktopClient.PIP_KEY.getKeyCode() == Keyboard.KEY_NONE ? new TextComponentTranslation("desktopscreens.pip.key_none")
                : new TextComponentTranslation("desktopscreens.pip.key", DesktopClient.key(DesktopClient.PIP_KEY));
        pageText = fitted(y, w, what, new TextComponentTranslation("desktopscreens.pip.how", DesktopClient.hostKey("P")), key);
        pageTextY = y;
        add(new Widget.Button(x, height - 24, w, new TextComponentTranslation("gui.back"), () -> switchTo(Mode.SETTINGS)));
    }

    /**
     * Paragraphs {@code width} wide from {@code y} down, a blank line between them, as many whole ones as fit above Back:
     * on a short screen the last ones are left out, rather than run under Back (Test 67, 2026-11-01: the PiP page's, once
     * it had the Hotbar row). The first shows as far as it fits.
     */
    private List<String> fitted(int y, int width, ITextComponent... paragraphs) {
        int room = (height - 24 - 2 - y) / (fontRenderer.FONT_HEIGHT + 1);
        List<String> lines = new ArrayList<>();
        for (ITextComponent paragraph : paragraphs) {
            List<String> next = wrap(paragraph, width);
            if (lines.isEmpty()) {
                lines.addAll(next.subList(0, Math.max(0, Math.min(next.size(), room))));
                continue;
            }
            if (lines.size() + 1 + next.size() > room) break;
            lines.add("");
            lines.addAll(next);
        }
        return lines;
    }

    /**
     * The streaming page: whether the game hides from screen capture while something shows its monitor (then that
     * picture shows the desktop behind it, and Discord and OBS see that too) or never does (the default; then the game
     * is covered with a note there, {@link GameCover}), and why.
     */
    private void addStreamWidgets() {
        int x = PAD, w = PANEL_WIDTH - 2 * PAD, y = 20;
        add(new Widget.Cycle<>(x, y, w, new TextComponentTranslation("desktopscreens.stream.hide"), () -> Arrays.asList(true, false),
                value -> new TextComponentTranslation(value ? "desktopscreens.stream.hide.needed" : "desktopscreens.stream.hide.never"),
                DesktopConfig.hideGame, value -> DesktopConfig.hideGame = value));
        y += 28;
        List<String> lines = new ArrayList<>(wrap(new TextComponentTranslation("desktopscreens.stream.why"), w));
        lines.add("");
        lines.addAll(wrap(new TextComponentTranslation("desktopscreens.stream.never"), w));
        lines.add("");
        lines.addAll(wrap(new TextComponentTranslation("desktopscreens.stream.tip"), w));
        pageText = lines;
        pageTextY = y;
        add(new Widget.Button(x, height - 24, w, new TextComponentTranslation("gui.back"), () -> switchTo(Mode.SETTINGS)));
    }

    private List<String> wrap(ITextComponent text, int width) {
        return fontRenderer.listFormattedStringToWidth(text.getFormattedText(), width);
    }

    private static ITextComponent onOff(boolean on) {
        return new TextComponentTranslation(on ? "options.on" : "options.off");
    }

    private void pickColor(int rgb) {
        DesktopConfig.borderColor = rgb;
        hexBox.setText(DesktopConfig.hex(rgb));
    }

    /** Grey out the controls that don't apply to the chosen border style. */
    private void updateActive() {
        BorderStyle style = DesktopConfig.borderStyle;
        boolean solid = style == BorderStyle.SOLID;
        thicknessSlider.enabled = style != BorderStyle.NONE;
        for (Widget.Swatch swatch : swatches) swatch.enabled = solid;
        hexBox.setEnabled(solid);
        if (!solid) hexBox.setFocused(false);
        rainbowSlider.enabled = style == BorderStyle.RAINBOW;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button instanceof Widget) ((Widget) button).onPress();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            // Escape steps back a page. While you use the desktop, the game doesn't get the keyboard at all.
            if (mode == Mode.STREAM || mode == Mode.SHARING || mode == Mode.PIP) switchTo(Mode.SETTINGS);
            else if (mode == Mode.SETTINGS && cameFromDesktop) switchTo(Mode.DESKTOP);
            else close();
            return;
        }
        // Enter adds the typed name on the sharing page.
        if ((keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) && mode == Mode.SHARING && sharingPage.enter()) return;
        GuiTextField box = textBox();
        if (box != null) box.textboxKeyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        // On the picture-in-picture page, the picture in the preview can be dragged anywhere.
        if (mode == Mode.PIP && mouseButton == 0 && PictureInPicture.startDrag(eventPixelX(), eventPixelY())) return;
        super.mouseClicked(mouseX, mouseY, mouseButton);
        GuiTextField box = textBox();
        if (box != null) box.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (mode == Mode.PIP && clickedMouseButton == 0 && PictureInPicture.dragging()) {
            pipCornerButton.set(PictureInPicture.drag(mc, eventPixelX(), eventPixelY()));
            return;
        }
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    /**
     * Where the mouse event being handled happened, in window pixels (from the top): the GUI units Minecraft passes on
     * are whole ones, a few pixels each.
     */
    private static double eventPixelX() {
        return Mouse.getEventX();
    }

    private double eventPixelY() {
        return mc.displayHeight - Mouse.getEventY() - 1; // LWJGL counts from the bottom
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (state == 0 && PictureInPicture.endDrag()) return;
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public void updateScreen() {
        // Back at the player's eyes: done.
        if (leaving && CameraGlide.done()) mc.displayGuiScreen(parent);
        // The server added or removed someone: show the list as it is now.
        if (mode == Mode.SHARING && sharingPage.changed()) rebuildWidgets();
        GuiTextField box = textBox();
        if (box != null) box.updateCursorCounter();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /** True while the real mouse controls the desktop, not Minecraft's cursor (the settings use that). */
    boolean usingDesktop() {
        return mode == Mode.DESKTOP;
    }

    /** Whether the world shows around the picture; otherwise this covers it and screens in the world can't be seen. */
    boolean showsWorld() {
        return gliding || session.showsWorld();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (gliding && !leaving && CameraGlide.done()) {
            // Arrived: the picture fills the view, and the real screen takes its place.
            gliding = false;
            atScreen = true;
            beginDesktop();
        }
        if (gliding) return; // the world, seen from the moving camera
        // Plain black instead of the menu background. The framed view leaves the world around the picture as it is.
        if (!session.showsWorld()) drawRect(0, 0, width, height, 0xFF000000);
        if (mode != Mode.DESKTOP) {
            int scale = new ScaledResolution(mc).getScaleFactor();
            int margin = PREVIEW_MARGIN * scale;
            int left = PANEL_WIDTH * scale + margin;
            if (mode == Mode.PIP) {
                PictureInPicture.renderPreview(mc, left, margin, mc.displayWidth - left - margin, mc.displayHeight - 2 * margin, mouseX, mouseY);
            } else {
                session.drawPreview(left, margin, mc.displayWidth - left - margin, mc.displayHeight - 2 * margin);
            }
            drawRect(0, 0, PANEL_WIDTH, height, 0xFF161616);
            if (mode == Mode.SETTINGS) {
                fontRenderer.drawStringWithShadow(new TextComponentTranslation("desktopscreens.settings.title").getFormattedText(), PAD, 8, 0xFFFFFF);
                fontRenderer.drawStringWithShadow(new TextComponentTranslation("desktopscreens.settings.color").getFormattedText(), PAD, colorLabelY,
                        DesktopConfig.borderStyle == BorderStyle.SOLID ? 0xE0E0E0 : 0x707070);
            } else if (mode == Mode.STREAM || mode == Mode.PIP) {
                String title = mode == Mode.PIP ? "desktopscreens.pip.title" : "desktopscreens.stream.title";
                fontRenderer.drawStringWithShadow(new TextComponentTranslation(title).getFormattedText(), PAD, 8, 0xFFFFFF);
                for (int i = 0; i < pageText.size(); i++) {
                    fontRenderer.drawStringWithShadow(pageText.get(i), PAD, pageTextY + i * (fontRenderer.FONT_HEIGHT + 1), 0xB0B0B0);
                }
            } else {
                fontRenderer.drawStringWithShadow(new TextComponentTranslation("desktopscreens.share.title").getFormattedText(), PAD, 8, 0xFFFFFF);
                sharingPage.render(fontRenderer, PAD);
            }
        } else {
            session.draw();
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        GuiTextField box = textBox();
        if (box != null) box.drawTextBox();
        // Who's watching: the HUD, which shows it otherwise, is hidden here.
        ShareStreamer.renderNotice(mc, width);
        if (session.takeWindowClosed()) {
            showFlash(new TextComponentTranslation("desktopscreens.hint.window_closed"));
            if (mode == Mode.SETTINGS) rebuildWidgets(); // the source button still offers the closed window
        }
        if (mode == Mode.DESKTOP) drawHints();
        String error = session.error();
        if (error != null) {
            drawBanner(height - fontRenderer.FONT_HEIGHT - 8, 0xFF6060, 255, new TextComponentTranslation("desktopscreens.error.capture", error));
        } else if (session.protectedContentHidden()) {
            // Otherwise it's just a black box with the subtitles still showing, which looks like a bug.
            drawBanner(height - 2 * (fontRenderer.FONT_HEIGHT + 3) - 6, 0xFFD37F, 255, new TextComponentTranslation("desktopscreens.hint.protected"),
                    new TextComponentTranslation("desktopscreens.hint.protected_how", DesktopClient.hostKey("M")));
        }
        drawTooltip(mouseX, mouseY);
    }

    /** The tooltip of the control under the mouse, if it has one; last, over everything. */
    private void drawTooltip(int mouseX, int mouseY) {
        if (mode == Mode.DESKTOP) return;
        for (GuiButton button : buttonList) {
            if (!(button instanceof Widget) || !button.isMouseOver() || ((Widget) button).tooltip == null) continue;
            drawHoveringText(wrap(((Widget) button).tooltip, 200), mouseX, mouseY);
            // Minecraft's tooltips switch item lighting on afterwards, for the item screens they're made for.
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableLighting();
            return;
        }
    }

    private void drawHints() {
        long now = Minecraft.getSystemTime();
        if (now < flashUntil + FADE_MILLIS) {
            drawBanner(8, 0xFFFFFF, fade(now, flashUntil), flash);
        } else if (now < hintUntil + FADE_MILLIS) {
            int alpha = fade(now, hintUntil);
            ITextComponent controlling = new TextComponentTranslation("desktopscreens.hint.controlling", shorten(session.sourceLabel()));
            if (session.inWorld()) {
                drawBanner(8, 0xFFFFFF, alpha, controlling, hostKeys("desktopscreens.hint.in_world_keys"));
            } else if (session.clickingThrough()) {
                drawBanner(8, 0xFFFFFF, alpha, controlling,
                        new TextComponentTranslation("desktopscreens.hint.in_place"),
                        hostKeys("desktopscreens.hint.in_place_keys"));
            } else {
                drawBanner(8, 0xFFFFFF, alpha, controlling,
                        hostKeys("desktopscreens.hint.keys"),
                        new TextComponentTranslation("desktopscreens.hint.view_keys", DesktopClient.plus("S"), DesktopConfig.scaling.label(),
                                DesktopClient.plus("F"),
                                new TextComponentTranslation(DesktopConfig.framed ? "desktopscreens.view.framed_short" : "desktopscreens.view.fullscreen"),
                                DesktopClient.plus(new TextComponentTranslation("desktopscreens.key.wheel"))));
            }
        }
    }

    /** "[Right Ctrl] Back to the game    [+O] Settings    [+M] Next monitor    [+W] One window" */
    private static ITextComponent hostKeys(String langKey) {
        return new TextComponentTranslation(langKey, DesktopClient.hostKey(), DesktopClient.plus("O"), DesktopClient.plus("M"), DesktopClient.plus("W"));
    }

    /** 255 while {@code until} is still ahead, then down to 0 over FADE_MILLIS. */
    private static int fade(long now, long until) {
        return now < until ? 255 : (int) Math.max(0, 255 - (now - until) * 255 / FADE_MILLIS);
    }

    /** Lines in a dark box, centered, {@code alpha} (0-255) as opaque as they are by now. */
    private void drawBanner(int y, int rgb, int alpha, ITextComponent... lines) {
        if (alpha < 4) return; // Minecraft's font draws alpha below 4 fully opaque
        String[] text = new String[lines.length];
        int w = 0;
        for (int i = 0; i < lines.length; i++) {
            text[i] = lines[i].getFormattedText();
            w = Math.max(w, fontRenderer.getStringWidth(text[i]));
        }
        w += 16;
        int lineStep = fontRenderer.FONT_HEIGHT + 3;
        drawRect((width - w) / 2, y - 4, (width + w) / 2, y + lines.length * lineStep + 1, (0xC0 * alpha / 255) << 24);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        for (int i = 0; i < lines.length; i++) {
            fontRenderer.drawStringWithShadow(text[i], (width - fontRenderer.getStringWidth(text[i])) / 2f, y + i * lineStep, alpha << 24 | rgb);
        }
        GlStateManager.disableBlend();
    }

    /** Back to where this was opened from: the game, or the screen before. Out of a screen, the camera glides back first. */
    void close() {
        if (leaving) return; // already gliding back out
        if (atScreen || gliding) {
            // Out of the in-place view first, then the camera glides back to the player's eyes, then this closes (updateScreen).
            leaving = true;
            gliding = true;
            atScreen = false;
            buttonList.clear(); // the settings panel, if it was open
            hexBox = null;
            session.stopInput();
            CameraGlide.out();
            return;
        }
        mc.displayGuiScreen(parent);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        DesktopConfig.save();
        session.close();
        FocusHandOff.end();
        CameraGlide.stop();
        if (hidGui) mc.gameSettings.hideGUI = hideGuiBefore;
    }
}
