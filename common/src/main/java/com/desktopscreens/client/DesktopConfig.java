package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Player settings, kept in config/desktopscreens.properties. */
final class DesktopConfig {
    static final int MIN_THICKNESS = 1, MAX_THICKNESS = 16;
    static final int MIN_RAINBOW_SECONDS = 1, MAX_RAINBOW_SECONDS = 20;
    static final int MIN_MARGIN = 1, MAX_MARGIN = 30;
    static final int MIN_PIP_SIZE = 15, MAX_PIP_SIZE = 50;
    static final int MIN_PIP_GAP = 0, MAX_PIP_GAP = 20;

    /**
     * What G and the tablet show: a monitor and maybe one window on it (Right Ctrl + M, Right Ctrl + W, the settings'
     * source button). Screens without their own show this too.
     */
    static final SourceChoice tablet = new SourceChoice(null, "", "", 0);
    /** What each screen shows that was given its own while it was used, by the screen's id. */
    static final Map<UUID, SourceChoice> screens = new HashMap<>();
    /** Picture-in-picture's corner, and its width in percent of the game window's (25 felt right in testing, 2026-09-26). */
    static PictureInPicture.Corner pipCorner = PictureInPicture.Corner.BOTTOM_RIGHT;
    static int pipSize = 25;
    /** How far it stays from the game window's edges (outside its border), in GUI units; 0 = right in the corner. */
    static int pipGap = 6;
    /**
     * Where it was dragged to on its settings page (used while {@link #pipCorner} is CUSTOM): how far along the room
     * it has, left to right and top to bottom, 0 to 1; -1 while it was never dragged anywhere but a corner.
     */
    static double pipX = -1, pipY = -1;
    /** What picture-in-picture shows (Right Ctrl + P), or null while nothing was pinned yet. */
    static SourceChoice pip;
    static Scaling scaling = Scaling.SHARP;
    /** Framed view: some of the world stays visible around the desktop. Otherwise it fills the window. */
    static boolean framed = false;
    /** How much world the framed view shows on each side, in percent of the window's shorter side. */
    static int frameMargin = 10;
    /** DXGI Desktop Duplication (up to the monitor's refresh rate); off means GDI only (about 60 fps). */
    static boolean fastCapture = true;
    /**
     * Hide the game from screen capture while a picture shows the monitor it's on, so that picture shows the desktop
     * behind it ({@link LiveCapture#hideGameIfOnIt}); Windows then hides it from Discord and OBS too. Off by default: a
     * streamer with the mod in their pack would lose the game from their stream, and show their desktop instead,
     * without ever seeing why (2026-09-28). Off, {@link GameCover} covers the game in those pictures.
     */
    static boolean hideGame = false;
    /**
     * Fullscreen, the game stays on screen when a window on another monitor gets the focus, and only minimizes for one
     * on its own ({@link FullscreenFocus}). No button (the panel is full); {@code fullscreen.stayForOtherMonitors} in
     * the config.
     */
    static boolean fullscreenStays = true;
    static BorderStyle borderStyle = BorderStyle.SOLID;
    /** 0xRRGGBB */
    static int borderColor = 0x808080;
    /** In screen pixels. */
    static int borderThickness = 2;
    /** How long one trip of the rainbow around the frame takes. */
    static int rainbowSeconds = 4;
    /** Players near your shared screens hear your PC (the switch on the sharing page). */
    static boolean shareSound = true;
    /**
     * Programs whose sound is never shared with a whole monitor: the beginnings of their file names, lower case.
     * Voice chat (someone on the same call would hear themselves again), audio routers, which play every program's
     * sound a second time, Minecraft's and the call's included, and Java: another Minecraft on this PC (a second
     * game, which may be the listener's own) would send its sound back round in a loop. A player can add their own.
     */
    static List<String> soundSkip = List.of("discord", "vesktop", "webcord", "teamspeak", "ts3client", "mumble", "skype",
            "teams", "ms-teams", "zoom", "whatsapp", "telegram", "signal", "slack", "element", "guilded", "viber",
            "nvidia broadcast", "rtx voice", "voicemeeter", "steelseries", "krisp", "java");
    /**
     * Other players' shared screens we see but don't hear ([RMB] on one), and ones we don't see at all
     * ([Shift + RMB]); by the screen's id, or its owner's for one without (all of theirs without one, together).
     */
    static Set<UUID> muted = new HashSet<>(), hidden = new HashSet<>();

    private static boolean loaded;

    private DesktopConfig() {}

    static void load() {
        if (loaded) return;
        loaded = true;
        Path file = file();
        if (!Files.exists(file)) return;
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException e) {
            DesktopScreens.LOG.warn("Couldn't read {}, using defaults", file, e);
            return;
        }
        tablet.monitor = p.getProperty("monitor", "").trim();
        tablet.window = p.getProperty("window", "").trim();
        tablet.handle = handle(p.getProperty("windowHandle"));
        for (String key : p.stringPropertyNames()) {
            // screen.<id>.monitor and screen.<id>.window
            if (!key.startsWith("screen.") || !key.endsWith(".monitor")) continue;
            try {
                UUID id = UUID.fromString(key.substring("screen.".length(), key.length() - ".monitor".length()));
                screens.put(id, new SourceChoice(id, p.getProperty(key).trim(), p.getProperty("screen." + id + ".window", "").trim(),
                        handle(p.getProperty("screen." + id + ".handle"))));
            } catch (IllegalArgumentException ignored) {
                // not an id: skip it
            }
        }
        if (p.getProperty("pip.monitor") != null) {
            pip = new SourceChoice(null, p.getProperty("pip.monitor").trim(), p.getProperty("pip.window", "").trim(),
                    handle(p.getProperty("pip.handle")));
        }
        pipCorner = enumValue(p, "pip.corner", pipCorner);
        pipX = fraction(p.getProperty("pip.x"));
        pipY = fraction(p.getProperty("pip.y"));
        if (pipX < 0 || pipY < 0) {
            pipX = pipY = -1;
            if (pipCorner == PictureInPicture.Corner.CUSTOM) pipCorner = PictureInPicture.Corner.BOTTOM_RIGHT;
        }
        pipSize = intValue(p, "pip.size", pipSize, MIN_PIP_SIZE, MAX_PIP_SIZE);
        pipGap = intValue(p, "pip.gap", pipGap, MIN_PIP_GAP, MAX_PIP_GAP);
        scaling = enumValue(p, "scaling", scaling);
        framed = Boolean.parseBoolean(p.getProperty("view.framed", Boolean.toString(framed)).trim());
        frameMargin = intValue(p, "view.frameMargin", frameMargin, MIN_MARGIN, MAX_MARGIN);
        fastCapture = Boolean.parseBoolean(p.getProperty("fastCapture", Boolean.toString(fastCapture)).trim());
        hideGame = Boolean.parseBoolean(p.getProperty("streaming.hideGame", Boolean.toString(hideGame)).trim());
        fullscreenStays = Boolean.parseBoolean(p.getProperty("fullscreen.stayForOtherMonitors", Boolean.toString(fullscreenStays)).trim());
        borderStyle = enumValue(p, "border.style", borderStyle);
        borderColor = color(p.getProperty("border.color"), borderColor);
        borderThickness = intValue(p, "border.thickness", borderThickness, MIN_THICKNESS, MAX_THICKNESS);
        rainbowSeconds = intValue(p, "border.rainbowSeconds", rainbowSeconds, MIN_RAINBOW_SECONDS, MAX_RAINBOW_SECONDS);
        shareSound = Boolean.parseBoolean(p.getProperty("sharing.sound", Boolean.toString(shareSound)).trim());
        String skip = p.getProperty("sharing.soundSkipPrograms");
        if (skip != null) {
            List<String> names = new ArrayList<>();
            for (String name : skip.split(",")) {
                if (!name.isBlank()) names.add(name.trim().toLowerCase(Locale.ROOT));
            }
            soundSkip = List.copyOf(names);
        }
        muted = players(p.getProperty("watching.muted"));
        hidden = players(p.getProperty("watching.hidden"));
    }

    /** A window handle saved as a number; 0 if there's none. */
    private static long handle(String text) {
        try {
            return text == null ? 0 : Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Set<UUID> players(String text) {
        Set<UUID> ids = new HashSet<>();
        if (text == null) return ids;
        for (String id : text.split(",")) {
            try {
                if (!id.isBlank()) ids.add(UUID.fromString(id.trim()));
            } catch (IllegalArgumentException ignored) {
                // not an id: skip it
            }
        }
        return ids;
    }

    private static String players(Set<UUID> ids) {
        StringBuilder sb = new StringBuilder();
        for (UUID id : ids) sb.append(sb.isEmpty() ? "" : ",").append(id);
        return sb.toString();
    }

    static void save() {
        Path file = file();
        Properties p = new Properties();
        p.setProperty("monitor", tablet.monitor);
        p.setProperty("window", tablet.window);
        p.setProperty("windowHandle", Long.toString(tablet.handle));
        for (SourceChoice s : screens.values()) {
            p.setProperty("screen." + s.screen + ".monitor", s.monitor);
            p.setProperty("screen." + s.screen + ".window", s.window);
            p.setProperty("screen." + s.screen + ".handle", Long.toString(s.handle));
        }
        if (pip != null) {
            p.setProperty("pip.monitor", pip.monitor);
            p.setProperty("pip.window", pip.window);
            p.setProperty("pip.handle", Long.toString(pip.handle));
        }
        p.setProperty("pip.corner", pipCorner.name());
        if (pipX >= 0 && pipY >= 0) {
            p.setProperty("pip.x", Double.toString(pipX));
            p.setProperty("pip.y", Double.toString(pipY));
        }
        p.setProperty("pip.size", Integer.toString(pipSize));
        p.setProperty("pip.gap", Integer.toString(pipGap));
        p.setProperty("scaling", scaling.name());
        p.setProperty("view.framed", Boolean.toString(framed));
        p.setProperty("view.frameMargin", Integer.toString(frameMargin));
        p.setProperty("fastCapture", Boolean.toString(fastCapture));
        p.setProperty("streaming.hideGame", Boolean.toString(hideGame));
        p.setProperty("fullscreen.stayForOtherMonitors", Boolean.toString(fullscreenStays));
        p.setProperty("border.style", borderStyle.name());
        p.setProperty("border.color", hex(borderColor));
        p.setProperty("border.thickness", Integer.toString(borderThickness));
        p.setProperty("border.rainbowSeconds", Integer.toString(rainbowSeconds));
        p.setProperty("sharing.sound", Boolean.toString(shareSound));
        p.setProperty("sharing.soundSkipPrograms", String.join(",", soundSkip));
        p.setProperty("watching.muted", players(muted));
        p.setProperty("watching.hidden", players(hidden));
        try {
            Files.createDirectories(file.getParent());
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                p.store(out, "Desktop Screens. Change these in game: Right Ctrl + O while using the desktop (or the key set in Controls instead of Right Ctrl).");
            }
        } catch (IOException e) {
            DesktopScreens.LOG.warn("Couldn't save {}", file, e);
        }
    }

    static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /** Parses "#RRGGBB" or "RRGGBB"; returns {@code fallback} for anything else. */
    static int color(String text, int fallback) {
        if (text == null) return fallback;
        String digits = text.startsWith("#") ? text.substring(1) : text;
        if (digits.length() != 6) return fallback;
        try {
            return Integer.parseInt(digits, 16);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static <E extends Enum<E>> E enumValue(Properties p, String key, E fallback) {
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), p.getProperty(key, fallback.name()).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static int intValue(Properties p, String key, int fallback, int min, int max) {
        try {
            return Mth.clamp(Integer.parseInt(p.getProperty(key, "").trim()), min, max);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 0 to 1, or -1 for anything else. */
    private static double fraction(String text) {
        try {
            double value = text == null ? -1 : Double.parseDouble(text.trim());
            return value >= 0 && value <= 1 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(DesktopScreens.MOD_ID + ".properties");
    }
}
