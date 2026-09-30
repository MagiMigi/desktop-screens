package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.desktopscreens.core.AppWindow;
import com.desktopscreens.core.DesktopInput;
import com.desktopscreens.core.Monitor;
import com.desktopscreens.core.Monitors;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
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
 * Opened with G while looking at a screen in the world, DESKTOP works differently, depending on the monitor used
 * (decided again whenever that changes, see {@link #enterDesktop}): on another monitor than Minecraft's, nothing is
 * drawn over the game and you use the screen where it is. On Minecraft's own monitor, the camera first glides in
 * until the picture fills the view, then the real screen shows through the game in place, and leaving glides back
 * out. The HUD and hand are hidden meanwhile.
 */
public final class DesktopScreen extends Screen {
    private static final long HINT_MILLIS = 6000, FLASH_MILLIS = 2500;
    private static final int PANEL_WIDTH = 172, PAD = 8, PREVIEW_MARGIN = 8;
    private static final int[] PALETTE = {
            0xFFFFFF, 0xAAAAAA, 0x808080, 0x404040, 0xFF4D4D, 0xFF9F40,
            0xFFE14D, 0x4CDB6B, 0x3FD0E0, 0x4A7BFF, 0xA45BFF, 0xFF6AD5};

    /**
     * SHARING is a page of the settings: whom a screen in the world is shared with (only when opened from one). PIP is
     * another: picture-in-picture's on/off, corner and size. STREAM: whether Discord and OBS may lose the game.
     */
    private enum Mode { DESKTOP, SETTINGS, SHARING, PIP, STREAM }

    private final DesktopSession session;
    private final Screen parent; // where closing goes; null = back to the game
    /** The screen in the world this was opened on with G, or null (G elsewhere, the tablet, the settings). */
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
    private Component flash;
    private long flashUntil;

    // Settings widgets, only while in SETTINGS mode.
    private IntSlider thicknessSlider, rainbowSlider;
    private EditBox hexBox;
    private final List<ColorSwatch> swatches = new ArrayList<>();
    private int colorLabelY;
    private SharingPage sharingPage;
    // The text below the buttons of the picture-in-picture and streaming pages.
    private List<FormattedCharSequence> pageText = List.of();
    private int pageTextY;
    private CycleButton<PictureInPicture.Corner> pipCornerButton;

    private DesktopScreen(DesktopSession session, Screen parent, Mode mode, ScreenGroups.Group screen) {
        super(Component.translatable("desktopscreens.settings.title"));
        this.session = session;
        this.parent = parent;
        this.mode = mode;
        this.screen = screen;
    }

    /** Opens straight into using the desktop. */
    static DesktopScreen desktop(DesktopSession session, Screen parent) {
        return new DesktopScreen(session, parent, Mode.DESKTOP, null);
    }

    /** Opens on the settings panel, with a live preview. */
    static DesktopScreen settings(DesktopSession session, Screen parent) {
        return new DesktopScreen(session, parent, Mode.SETTINGS, null);
    }

    /** Uses a screen in the world that's on for this player. */
    static DesktopScreen onScreen(DesktopSession session, ScreenGroups.Group screen) {
        return new DesktopScreen(session, null, Mode.DESKTOP, screen);
    }

    @Override
    protected void init() {
        if (!started) { // init() runs again on every resize; only start once
            started = true;
            // Covers the settings too: while any desktop view is open, the game must not minimize or pause
            // itself just because you clicked something on another monitor.
            FocusHandOff.begin(minecraft);
            if (screen != null) {
                hideGuiBefore = minecraft.options.hideGui;
                minecraft.options.hideGui = true;
                hidGui = true;
            }
            if (mode == Mode.DESKTOP) enterDesktop();
        }
        if (mode == Mode.SETTINGS) addSettingsWidgets();
        else if (mode == Mode.SHARING) sharingPage.addWidgets(this::addRenderableWidget, font, PAD, PANEL_WIDTH - 2 * PAD, height);
        else if (mode == Mode.PIP) addPipWidgets();
        else if (mode == Mode.STREAM) addStreamWidgets();
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
                CameraGlide.in(CameraGlide.facing(screen, screen.pictureRect(size[0], size[1]), minecraft));
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

    private void beginDesktop() {
        session.startInput(); // only works once the screen is open: that's when Minecraft lets go of the mouse
        hintUntil = Util.getMillis() + HINT_MILLIS;
    }

    private void switchTo(Mode newMode) {
        if (newMode == mode) return;
        // The sharing page and back doesn't change where Esc in the settings goes.
        if (newMode == Mode.DESKTOP || mode == Mode.DESKTOP) cameFromDesktop = mode == Mode.DESKTOP;
        if (newMode == Mode.SHARING && sharingPage == null) sharingPage = new SharingPage(minecraft, screen, () -> switchTo(Mode.SETTINGS));
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
                case DesktopInput.VK_O -> switchTo(Mode.SETTINGS);
                case DesktopInput.VK_W -> toggleWindow();
                case DesktopInput.VK_M -> cycleMonitor();
                case DesktopInput.VK_P -> pinPicture();
                default -> showFlash(Component.translatable("desktopscreens.hint.not_in_world", DesktopClient.hostKey()));
            }
            return;
        }
        switch (virtualKey) {
            case DesktopInput.VK_S -> cycleScaling();
            case DesktopInput.VK_M -> cycleMonitor();
            case DesktopInput.VK_O -> switchTo(Mode.SETTINGS);
            case DesktopInput.VK_F -> toggleFramed();
            case DesktopInput.VK_W -> toggleWindow();
            case DesktopInput.VK_P -> pinPicture();
            case DesktopInput.VK_UP, DesktopInput.VK_OEM_PLUS, DesktopInput.VK_ADD -> zoom(1);
            case DesktopInput.VK_DOWN, DesktopInput.VK_OEM_MINUS, DesktopInput.VK_SUBTRACT -> zoom(-1);
            case DesktopInput.VK_0, DesktopInput.VK_NUMPAD0 -> zoom(0);
            default -> {}
        }
    }

    /**
     * You switched to Minecraft yourself (Alt+Tab, its taskbar button): back to the game, as the host key does. The way
     * back for keyboards without the host key. Game thread.
     */
    void onGameChosen() {
        if (mode != Mode.DESKTOP || !session.controlling()) return; // the settings took the mouse and keyboard back meanwhile
        DesktopScreens.LOG.info("Back to the game: Minecraft was switched to (Alt+Tab or its taskbar button)");
        onClose();
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
            showFlash(Component.translatable("desktopscreens.hint.zoom_in_place"));
            return;
        }
        if (steps == 0) session.resetZoom();
        else session.zoomBy(steps);
        showFlash(Component.translatable(session.zoomed() ? "desktopscreens.hint.zoom_reset" : "desktopscreens.hint.zoom",
                session.zoomLabel(), DesktopClient.hostKey("0")));
    }

    /** Right Ctrl + F: between fullscreen and framed, with the margin from the settings. */
    private void toggleFramed() {
        DesktopConfig.framed = !DesktopConfig.framed;
        DesktopConfig.save();
        showFlash(Component.translatable(session.clickingThrough() ? "desktopscreens.hint.view_in_place" : "desktopscreens.hint.view",
                viewLabel(DesktopConfig.framed ? DesktopConfig.frameMargin : 0)));
    }

    /** Right Ctrl + W: just the window under the mouse, or the whole monitor again. */
    private void toggleWindow() {
        if (session.window() != null) {
            session.setWindow(null);
            showFlash(Component.translatable("desktopscreens.hint.whole_monitor", monitorLabel(session.target())));
        } else {
            AppWindow w = session.windowUnderPointer();
            if (w == null) {
                showFlash(Component.translatable("desktopscreens.hint.no_window", DesktopClient.hostKey("W")));
                return;
            }
            session.setWindow(w);
            showFlash(Component.translatable("desktopscreens.hint.one_window", shorten(w.label()), DesktopClient.hostKey("W")));
        }
        DesktopConfig.save();
    }

    /**
     * Right Ctrl + P: the window under the mouse, or over the empty desktop this whole monitor, as picture-in-picture
     * (it shows once you're back in the game); over the one it shows already, off again.
     */
    private void pinPicture() {
        if (!DesktopClient.hasTablet(minecraft)) {
            showFlash(Component.translatable("desktopscreens.pip.needs_tablet"));
            return;
        }
        AppWindow w = session.windowUnderPointer();
        Monitor on = w != null ? Monitors.of(w.handle()) : null;
        Monitor monitor = on != null ? on : session.target();
        if (PictureInPicture.pinOrHide(w, monitor)) {
            showFlash(Component.translatable("desktopscreens.pip.on", w != null ? Component.literal(shorten(w.label())) : monitorLabel(monitor),
                    DesktopClient.hostKey("P")));
        } else {
            showFlash(Component.translatable("desktopscreens.pip.off"));
        }
    }

    /** Window titles can be long (a video's, say). */
    private static String shorten(String text) {
        return text.length() <= 48 ? text : text.substring(0, 45) + "...";
    }

    /** "Fullscreen" for 0, otherwise "Framed, 10% margin". */
    private static Component viewLabel(int margin) {
        return margin == 0 ? Component.translatable("desktopscreens.view.fullscreen")
                : Component.translatable("desktopscreens.view.framed", margin);
    }

    private void cycleScaling() {
        DesktopConfig.scaling = DesktopConfig.scaling.next();
        DesktopConfig.save();
        showFlash(Component.translatable(session.clickingThrough() ? "desktopscreens.hint.scaling_in_place" : "desktopscreens.hint.scaling",
                DesktopConfig.scaling.label()));
    }

    /** Moves to the next monitor and takes control of it. */
    private void cycleMonitor() {
        List<Monitor> monitors = Monitors.list();
        if (monitors.size() < 2) {
            showFlash(Component.translatable("desktopscreens.hint.one_monitor"));
            return;
        }
        int index = 0;
        for (int i = 0; i < monitors.size(); i++) {
            if (monitors.get(i).sameAs(session.target())) index = i;
        }
        setMonitor(monitors.get((index + 1) % monitors.size()));
        showFlash(Component.translatable("desktopscreens.hint.monitor", monitorLabel(session.target())));
        // The hint differs on Minecraft's own monitor (clicks go through the game there), so show it again after the flash.
        hintUntil = Util.getMillis() + HINT_MILLIS;
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
    private Component monitorLabel(Monitor monitor) {
        return session.isGameMonitor(monitor)
                ? Component.translatable("desktopscreens.settings.monitor_game", monitor.label())
                : Component.literal(monitor.label());
    }

    /** An entry in the settings' source button: a whole monitor, or the one window shown (only while there is one). */
    private record Source(Monitor monitor, AppWindow window) {}

    /**
     * The longest label that fits the button. Minecraft scrolls one that doesn't back and forth, so it showed cut off at
     * one end, like "SPLAY1 1920x1080 (Minecraft)" for Minecraft's own monitor (a screenshot for the mod page, 2026-09-29).
     */
    private Component sourceLabel(Source source) {
        int room = PANEL_WIDTH - 2 * PAD - 4; // the button, less the margin its text keeps on each side
        if (source.window() != null) {
            Component full = Component.translatable("desktopscreens.settings.window", shorten(source.window().label()));
            if (font.width(full) <= room) return full;
            return Component.literal(font.plainSubstrByWidth(source.window().label(), room - font.width("...")) + "...");
        }
        Monitor m = source.monitor();
        Component label = monitorLabel(m);
        Component shortest = session.isGameMonitor(m)
                ? Component.translatable("desktopscreens.settings.monitor_game", m.shortLabel()) : Component.literal(m.shortLabel());
        for (Component c : new Component[] {Component.translatable("desktopscreens.settings.monitor_value", label), label}) {
            if (font.width(c) <= room) return c;
        }
        return shortest;
    }

    private void showFlash(Component text) {
        flash = text;
        flashUntil = Util.getMillis() + FLASH_MILLIS;
    }

    private void addSettingsWidgets() {
        int x = PAD, w = PANEL_WIDTH - 2 * PAD, y = 20;
        List<Monitor> monitors = Monitors.list(); // read fresh, in case one was plugged in or unplugged
        Monitor current = monitors.stream().filter(m -> m.sameAs(session.target())).findFirst().orElse(null);
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
        addRenderableWidget(CycleButton.builder(this::sourceLabel)
                .withValues(sources)
                .withInitialValue(initial)
                .displayOnlyValue()
                .withTooltip(value -> Tooltip.create(Component.translatable("desktopscreens.settings.source.tooltip", DesktopClient.hostKeyName())))
                .create(x, y, w, 20, Component.translatable("desktopscreens.settings.monitor"), (button, value) -> {
                    if (value.window() == null) {
                        setMonitor(value.monitor());
                    } else if (value.window().alive()) {
                        session.setWindow(value.window());
                        DesktopConfig.save();
                    }
                }));
        y += 22;
        addRenderableWidget(CycleButton.builder(Scaling::label)
                .withValues(Scaling.values())
                .withInitialValue(DesktopConfig.scaling)
                .create(x, y, w, 20, Component.translatable("desktopscreens.settings.scaling"), (button, value) -> DesktopConfig.scaling = value));
        y += 22;
        // One slider for both, to save a row: all the way left is fullscreen, anything else is framed with that margin.
        IntSlider view = addRenderableWidget(new IntSlider(x, y, w, 20,
                value -> Component.translatable("desktopscreens.settings.view", viewLabel(value)),
                0, DesktopConfig.MAX_MARGIN, DesktopConfig.framed ? DesktopConfig.frameMargin : 0,
                value -> {
                    DesktopConfig.framed = value > 0;
                    if (value > 0) DesktopConfig.frameMargin = value;
                }));
        view.setTooltip(Tooltip.create(Component.translatable("desktopscreens.settings.view.tooltip", DesktopClient.hostKeyName())));
        y += 22;
        // The streaming page shares this row, as picture-in-picture's does "Use the desktop": there's no room for another.
        int streamWidth = 50;
        addRenderableWidget(CycleButton.onOffBuilder(DesktopConfig.fastCapture)
                .withTooltip(value -> Tooltip.create(Component.translatable("desktopscreens.settings.fast_capture.tooltip")))
                .create(x, y, w - streamWidth - 2, 20, Component.translatable("desktopscreens.settings.fast_capture"), (button, value) -> {
                    DesktopConfig.fastCapture = value;
                    session.restartCapture();
                }));
        addRenderableWidget(Button.builder(Component.translatable("desktopscreens.settings.stream"), button -> switchTo(Mode.STREAM))
                .bounds(x + w - streamWidth, y, streamWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("desktopscreens.settings.stream.tooltip"))).build());
        y += 22;
        addRenderableWidget(CycleButton.builder(BorderStyle::label)
                .withValues(BorderStyle.values())
                .withInitialValue(DesktopConfig.borderStyle)
                .create(x, y, w, 20, Component.translatable("desktopscreens.settings.border"), (button, value) -> {
                    DesktopConfig.borderStyle = value;
                    updateActive();
                }));
        y += 22;
        thicknessSlider = addRenderableWidget(new IntSlider(x, y, w, 20, "desktopscreens.settings.thickness",
                DesktopConfig.MIN_THICKNESS, DesktopConfig.MAX_THICKNESS, DesktopConfig.borderThickness,
                value -> DesktopConfig.borderThickness = value));
        y += 22;

        // The label, with the hex box on the same row, then the swatches in one row: the panel must still fit a
        // maximized 1080p window at GUI scale 4 (about 254 units tall). It ends at 251 now; there's no room for another row.
        colorLabelY = y + 4;
        int hexWidth = 54;
        hexBox = addRenderableWidget(new EditBox(font, x + w - hexWidth + 1, y, hexWidth - 2, 16,
                Component.translatable("desktopscreens.settings.color_hex")));
        hexBox.setMaxLength(7);
        hexBox.setValue(DesktopConfig.hex(DesktopConfig.borderColor));
        hexBox.setFilter(text -> text.matches("#?[0-9a-fA-F]{0,6}"));
        hexBox.setResponder(text -> DesktopConfig.borderColor = DesktopConfig.color(text, DesktopConfig.borderColor));
        y += 20;
        swatches.clear();
        int gap = 2, size = (w - (PALETTE.length - 1) * gap) / PALETTE.length;
        for (int i = 0; i < PALETTE.length; i++) {
            swatches.add(addRenderableWidget(new ColorSwatch(x + i * (size + gap), y, size,
                    PALETTE[i], () -> DesktopConfig.borderColor, this::pickColor)));
        }
        y += size + 4;
        rainbowSlider = addRenderableWidget(new IntSlider(x, y, w, 20, "desktopscreens.settings.rainbow_speed",
                DesktopConfig.MIN_RAINBOW_SECONDS, DesktopConfig.MAX_RAINBOW_SECONDS, DesktopConfig.rainbowSeconds,
                value -> DesktopConfig.rainbowSeconds = value));
        y += 22;

        // Picture-in-picture's page shares this row: a narrow button, so "Use the desktop" keeps room for its words.
        int pipWidth = 50;
        Button use = addRenderableWidget(Button.builder(Component.translatable("desktopscreens.settings.use_desktop"), button -> switchTo(Mode.DESKTOP))
                .bounds(x, y, w - pipWidth - 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("desktopscreens.settings.pip"), button -> switchTo(Mode.PIP))
                .bounds(x + w - pipWidth, y, pipWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("desktopscreens.settings.pip.tooltip"))).build());
        // Like G: back to where the settings were opened from, or with a tablet on you (not from the mods list alone).
        if (!cameFromDesktop && screen == null && !DesktopClient.hasTablet(minecraft)) {
            use.active = false;
            use.setTooltip(Tooltip.create(Component.translatable("desktopscreens.settings.use_desktop.needs_tablet")));
        }
        y += 22;
        if (screen != null) {
            // Opened from a screen in the world (yours, or G wouldn't use it): sharing it shares this row with Done.
            int half = (w - 2) / 2;
            Button sharing = addRenderableWidget(Button.builder(
                    Component.translatable("desktopscreens.settings.sharing", SharingPage.count(minecraft, screen)),
                    button -> switchTo(Mode.SHARING)).bounds(x, y, half, 20).build());
            sharing.setTooltip(Tooltip.create(Component.translatable("desktopscreens.settings.sharing.tooltip")));
            addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).bounds(x + w - half, y, half, 20).build());
        } else {
            addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).bounds(x, y, w, 20).build());
        }
        updateActive();
    }

    /**
     * Picture-in-picture's page: on or off (so it works without binding its key), the corner, the size, what's pinned
     * and how to pin something else, and Back. The preview on the right shows it in a small game window.
     */
    private void addPipWidgets() {
        int x = PAD, w = PANEL_WIDTH - 2 * PAD, y = 20;
        DesktopConfig.load();
        CycleButton<Boolean> shown = addRenderableWidget(CycleButton.onOffBuilder(PictureInPicture.shown())
                .create(x, y, w, 20, Component.translatable("desktopscreens.pip.shown"), (button, value) -> PictureInPicture.setShown(value)));
        if (DesktopConfig.pip == null) {
            shown.active = false;
            shown.setTooltip(Tooltip.create(Component.translatable("desktopscreens.pip.nothing", DesktopClient.hostKey("P"))));
        }
        y += 22;
        // Its choices are asked for on every click: the dragged spot joins the corners once there is one.
        pipCornerButton = addRenderableWidget(CycleButton.builder(PictureInPicture.Corner::label)
                .withValues(new CycleButton.ValueListSupplier<PictureInPicture.Corner>() {
                    @Override
                    public List<PictureInPicture.Corner> getSelectedList() {
                        return PictureInPicture.Corner.choices();
                    }

                    @Override
                    public List<PictureInPicture.Corner> getDefaultList() {
                        return PictureInPicture.Corner.choices();
                    }
                })
                .withInitialValue(DesktopConfig.pipCorner)
                .create(x, y, w, 20, Component.translatable("desktopscreens.pip.corner"), (button, value) -> DesktopConfig.pipCorner = value));
        PictureInPicture.resetPreview();
        y += 22;
        // Size and the gap share a row, so the text below still fits a maximized window at GUI scale 4.
        int half = (w - 2) / 2;
        IntSlider size = addRenderableWidget(new IntSlider(x, y, half, 20, "desktopscreens.pip.size", DesktopConfig.MIN_PIP_SIZE,
                DesktopConfig.MAX_PIP_SIZE, DesktopConfig.pipSize, value -> DesktopConfig.pipSize = value));
        size.setTooltip(Tooltip.create(Component.translatable("desktopscreens.pip.size.tooltip")));
        IntSlider gap = addRenderableWidget(new IntSlider(x + w - half, y, half, 20, "desktopscreens.pip.gap", DesktopConfig.MIN_PIP_GAP,
                DesktopConfig.MAX_PIP_GAP, DesktopConfig.pipGap, value -> DesktopConfig.pipGap = value));
        gap.setTooltip(Tooltip.create(Component.translatable("desktopscreens.pip.gap.tooltip")));
        y += 28;
        // What's pinned (a window by its title, found once here: that walks every window), how to pin, and its key.
        SourceChoice pinned = DesktopConfig.pip;
        Component what;
        if (pinned == null) {
            what = Component.translatable("desktopscreens.pip.pinned_none");
        } else if (!pinned.window.isEmpty()) {
            AppWindow window = AppWindow.find(pinned.window, pinned.handle);
            what = Component.translatable("desktopscreens.pip.pinned", window != null ? shorten(window.label())
                    : Component.translatable("desktopscreens.pip.pinned_closed"));
        } else {
            Monitor monitor = Monitors.byName(pinned.monitor);
            what = Component.translatable("desktopscreens.pip.pinned", monitor != null ? monitorLabel(monitor) : pinned.monitor);
        }
        Component key = DesktopClient.PIP_KEY.isUnbound() ? Component.translatable("desktopscreens.pip.key_none")
                : Component.translatable("desktopscreens.pip.key", DesktopClient.key(DesktopClient.PIP_KEY));
        List<FormattedCharSequence> lines = new ArrayList<>(font.split(what, w));
        lines.add(FormattedCharSequence.EMPTY);
        lines.addAll(font.split(Component.translatable("desktopscreens.pip.how", DesktopClient.hostKey("P")), w));
        lines.add(FormattedCharSequence.EMPTY);
        lines.addAll(font.split(key, w));
        pageText = lines;
        pageTextY = y;
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> switchTo(Mode.SETTINGS)).bounds(x, height - 24, w, 20).build());
    }

    /**
     * The streaming page: whether the game hides from screen capture while something shows its monitor (then that
     * picture shows the desktop behind it, and Discord and OBS see that too) or never does (the default; then the game
     * is covered with a note there, {@link GameCover}), and why.
     */
    private void addStreamWidgets() {
        int x = PAD, w = PANEL_WIDTH - 2 * PAD, y = 20;
        addRenderableWidget(CycleButton.booleanBuilder(Component.translatable("desktopscreens.stream.hide.needed"),
                        Component.translatable("desktopscreens.stream.hide.never"))
                .withInitialValue(DesktopConfig.hideGame)
                .create(x, y, w, 20, Component.translatable("desktopscreens.stream.hide"), (button, value) -> DesktopConfig.hideGame = value));
        y += 28;
        List<FormattedCharSequence> lines = new ArrayList<>(font.split(Component.translatable("desktopscreens.stream.why"), w));
        lines.add(FormattedCharSequence.EMPTY);
        lines.addAll(font.split(Component.translatable("desktopscreens.stream.never"), w));
        lines.add(FormattedCharSequence.EMPTY);
        lines.addAll(font.split(Component.translatable("desktopscreens.stream.tip"), w));
        pageText = lines;
        pageTextY = y;
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> switchTo(Mode.SETTINGS)).bounds(x, height - 24, w, 20).build());
    }

    private void pickColor(int rgb) {
        DesktopConfig.borderColor = rgb;
        hexBox.setValue(DesktopConfig.hex(rgb));
    }

    /** Grey out the controls that don't apply to the chosen border style. */
    private void updateActive() {
        BorderStyle style = DesktopConfig.borderStyle;
        boolean solid = style == BorderStyle.SOLID;
        thicknessSlider.active = style != BorderStyle.NONE;
        for (ColorSwatch swatch : swatches) swatch.active = solid;
        hexBox.active = solid;
        hexBox.setEditable(solid);
        rainbowSlider.active = style == BorderStyle.RAINBOW;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (mode == Mode.SHARING || mode == Mode.PIP || mode == Mode.STREAM) switchTo(Mode.SETTINGS);
            else if (mode == Mode.SETTINGS && cameFromDesktop) switchTo(Mode.DESKTOP);
            else onClose();
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && mode == Mode.SHARING && sharingPage.enter()) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** On the picture-in-picture page, the picture in the preview can be dragged anywhere. */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        return mode == Mode.PIP && button == GLFW.GLFW_MOUSE_BUTTON_LEFT && PictureInPicture.startDrag(minecraft, mouseX, mouseY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (mode == Mode.PIP && button == GLFW.GLFW_MOUSE_BUTTON_LEFT && PictureInPicture.dragging()) {
            pipCornerButton.setValue(PictureInPicture.drag(minecraft, mouseX, mouseY));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && PictureInPicture.endDrag()) return true;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** True while the real mouse controls the desktop, not Minecraft's cursor (the settings and sharing pages use that). */
    boolean usingDesktop() {
        return mode == Mode.DESKTOP;
    }

    /** Whether the world shows around the picture; otherwise this covers it and screens in the world can't be seen. */
    boolean showsWorld() {
        return gliding || session.showsWorld();
    }

    @Override
    public void tick() {
        // Back at the player's eyes: done.
        if (leaving && CameraGlide.done()) minecraft.setScreen(parent);
        // The server added or removed someone: show the list as it is now.
        if (mode == Mode.SHARING && sharingPage.changed()) rebuildWidgets();
    }

    /** Screen.render draws this first, then the widgets on top. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (gliding && !leaving && CameraGlide.done()) {
            // Arrived: the picture fills the view, and the real screen takes its place.
            gliding = false;
            atScreen = true;
            beginDesktop();
        }
        if (gliding) return; // the world, seen from the moving camera
        // Plain black instead of the menu blur. The framed view leaves the world around the picture as it is.
        if (!session.showsWorld()) g.fill(0, 0, width, height, 0xFF000000);
        if (mode != Mode.DESKTOP) {
            Window window = minecraft.getWindow();
            double scale = window.getGuiScale();
            int margin = (int) Math.round(PREVIEW_MARGIN * scale);
            int left = (int) Math.round(PANEL_WIDTH * scale) + margin;
            if (mode == Mode.PIP) {
                PictureInPicture.renderPreview(minecraft, g, left, margin, window.getWidth() - left - margin, window.getHeight() - 2 * margin,
                        mouseX, mouseY);
            } else {
                session.drawPreview(g, left, margin, window.getWidth() - left - margin, window.getHeight() - 2 * margin);
            }
            g.fill(0, 0, PANEL_WIDTH, height, 0xFF161616);
            if (mode == Mode.SETTINGS) {
                g.drawString(font, title, PAD, 8, 0xFFFFFF);
                g.drawString(font, Component.translatable("desktopscreens.settings.color"), PAD, colorLabelY,
                        DesktopConfig.borderStyle == BorderStyle.SOLID ? 0xE0E0E0 : 0x707070);
            } else if (mode == Mode.PIP || mode == Mode.STREAM) {
                g.drawString(font, Component.translatable(mode == Mode.PIP ? "desktopscreens.pip.title" : "desktopscreens.stream.title"), PAD, 8, 0xFFFFFF);
                for (int i = 0; i < pageText.size(); i++) g.drawString(font, pageText.get(i), PAD, pageTextY + i * (font.lineHeight + 1), 0xB0B0B0);
            } else {
                g.drawString(font, Component.translatable("desktopscreens.share.title"), PAD, 8, 0xFFFFFF);
                sharingPage.render(g, font, PAD);
            }
        } else {
            session.draw(g);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (session.takeWindowClosed()) {
            showFlash(Component.translatable("desktopscreens.hint.window_closed"));
            if (mode == Mode.SETTINGS) rebuildWidgets(); // the source button still offers the closed window
        }
        ShareStreamer.renderNotice(minecraft, g, width); // the HUD, which shows it otherwise, is hidden here
        if (gliding) return;
        if (mode == Mode.DESKTOP) drawHints(g);
        String error = session.error();
        if (error != null) {
            drawBanner(g, height - font.lineHeight - 8, 0xFF6060, Component.translatable("desktopscreens.error.capture", error));
        } else if (session.protectedContentHidden()) {
            // Otherwise it's just a black box with the subtitles still showing, which looks like a bug.
            drawBanner(g, height - 2 * (font.lineHeight + 3) - 6, 0xFFD37F, Component.translatable("desktopscreens.hint.protected"),
                    Component.translatable("desktopscreens.hint.protected_how", DesktopClient.hostKey("M")));
        }
    }

    private void drawHints(GuiGraphics g) {
        long now = Util.getMillis();
        if (now < flashUntil) {
            drawBanner(g, 8, 0xFFFFFF, flash);
        } else if (now < hintUntil) {
            Component scaling = DesktopConfig.scaling.label();
            Component controlling = Component.translatable("desktopscreens.hint.controlling", shorten(session.sourceLabel()));
            if (session.inWorld()) {
                drawBanner(g, 8, 0xFFFFFF, controlling, hostKeys("desktopscreens.hint.in_world_keys"));
            } else if (session.clickingThrough()) {
                drawBanner(g, 8, 0xFFFFFF, controlling,
                        Component.translatable("desktopscreens.hint.in_place"),
                        hostKeys("desktopscreens.hint.in_place_keys"));
            } else {
                drawBanner(g, 8, 0xFFFFFF, controlling,
                        hostKeys("desktopscreens.hint.keys"),
                        Component.translatable("desktopscreens.hint.view_keys", DesktopClient.plus("S"), scaling, DesktopClient.plus("F"),
                                Component.translatable(DesktopConfig.framed ? "desktopscreens.view.framed_short" : "desktopscreens.view.fullscreen"),
                                DesktopClient.plus(Component.translatable("desktopscreens.key.wheel"))));
            }
        }
    }

    /** "[Right Ctrl] Back to the game    [+O] Settings    [+M] Next monitor    [+W] One window" */
    private static Component hostKeys(String langKey) {
        return Component.translatable(langKey, DesktopClient.hostKey(), DesktopClient.plus("O"), DesktopClient.plus("M"), DesktopClient.plus("W"));
    }

    private void drawBanner(GuiGraphics g, int y, int color, Component... lines) {
        int w = 0;
        for (Component line : lines) w = Math.max(w, font.width(line));
        w += 16;
        int lineStep = font.lineHeight + 3;
        g.fill((width - w) / 2, y - 4, (width + w) / 2, y + lines.length * lineStep + 1, 0xC0000000);
        for (int i = 0; i < lines.length; i++) {
            g.drawCenteredString(font, lines[i], width / 2, y + i * lineStep, color);
        }
    }

    @Override
    public void onClose() {
        if (leaving) return; // already gliding back out
        if (atScreen || gliding) {
            // Out of the in-place view first, then the camera glides back to the player's eyes, then this closes (tick).
            leaving = true;
            gliding = true;
            atScreen = false;
            clearWidgets(); // the settings panel, if it was open
            session.stopInput();
            CameraGlide.out();
            return;
        }
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        DesktopConfig.save();
        session.close();
        FocusHandOff.end();
        CameraGlide.stop();
        if (hidGui) minecraft.options.hideGui = hideGuiBefore;
    }
}
