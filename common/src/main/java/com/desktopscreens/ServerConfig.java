package com.desktopscreens;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.io.Reader;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Properties;

/**
 * The server's settings, in {@code config/desktopscreens-server.properties} in the server's folder (in singleplayer,
 * the game's own config folder): the Screen Builder's biggest screen, and the limits for sharing screens, whose
 * pictures pass through the server. Written with the defaults the first time; settings a newer version added are
 * appended to an existing file, with their explanation. Read once per server start.
 */
public final class ServerConfig {
    private record Setting(String key, String value, String comment) {}

    private static final List<Setting> SETTINGS = List.of(
            new Setting("builder.maxWidth", "32", "The biggest screen the Screen Builder may build, in blocks (1 to 64)."),
            new Setting("builder.maxHeight", "32", null),
            new Setting("builder.range", "32",
                    "How far away a player may build, move, resize or take down screens with the Screen Builder, in blocks (8 to 128)."),
            new Setting("sharing.enabled", "true",
                    "Whether players may share their screens with other players. The pictures go through this server."),
            new Setting("sharing.maxFps", "30", "How many pictures per second a shared screen may send at most (1 to 60)."),
            new Setting("sharing.maxWidth", "1280",
                    "The biggest shared picture, in pixels; bigger ones are scaled down (320x180 to 1920x1080)."),
            new Setting("sharing.maxHeight", "720", null),
            new Setting("sharing.maxStreams", "4",
                    "How many different pictures one player may share at once (1 to 8): screens showing different things each send their own."),
            new Setting("sharing.sound", "true",
                    "Whether shared screens also send their owner's PC sound to the players near them (about 100 kbit/s each)."));

    public final int builderMaxWidth, builderMaxHeight, builderRange;
    public final boolean sharing, shareSound;
    public final int shareMaxFps, shareMaxWidth, shareMaxHeight, shareMaxStreams;

    /** The server the settings were read for; weak, so a closed singleplayer world isn't kept in memory. */
    private static WeakReference<MinecraftServer> loadedFor = new WeakReference<>(null);
    private static ServerConfig loaded;

    private ServerConfig(Properties p) {
        builderMaxWidth = intValue(p, "builder.maxWidth", 1, 64);
        builderMaxHeight = intValue(p, "builder.maxHeight", 1, 64);
        builderRange = intValue(p, "builder.range", 8, 128);
        sharing = Boolean.parseBoolean(value(p, "sharing.enabled"));
        shareMaxFps = intValue(p, "sharing.maxFps", 1, 60);
        shareMaxWidth = intValue(p, "sharing.maxWidth", 320, 1920);
        shareMaxHeight = intValue(p, "sharing.maxHeight", 180, 1080);
        shareMaxStreams = intValue(p, "sharing.maxStreams", 1, 8);
        shareSound = sharing && Boolean.parseBoolean(value(p, "sharing.sound"));
    }

    /** This server's settings, read the first time they're needed. */
    public static synchronized ServerConfig of(MinecraftServer server) {
        if (loadedFor.get() != server || loaded == null) {
            loadedFor = new WeakReference<>(server);
            loaded = load(server.getServerDirectory().resolve("config").resolve(DesktopScreens.MOD_ID + "-server.properties"));
        }
        return loaded;
    }

    private static ServerConfig load(Path file) {
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(in);
            } catch (IOException e) {
                DesktopScreens.LOG.warn("Couldn't read {}, using defaults", file, e);
            }
        }
        addMissing(file, p);
        ServerConfig config = new ServerConfig(p);
        DesktopScreens.LOG.info("Server settings: the Screen Builder builds up to {} x {} blocks, up to {} blocks away; sharing screens is {}{}",
                config.builderMaxWidth, config.builderMaxHeight, config.builderRange, config.sharing ? "on" : "off",
                config.sharing ? ", up to " + config.shareMaxFps + " pictures a second at " + config.shareMaxWidth + "x" + config.shareMaxHeight
                        + ", " + config.shareMaxStreams + " streams per player"
                        + (config.shareSound ? ", with sound" : ", without sound") : "");
        return config;
    }

    /** Writes the settings the file doesn't have yet (all of them the first time), with their explanation. */
    private static void addMissing(Path file, Properties p) {
        StringBuilder text = new StringBuilder();
        if (!Files.exists(file)) text.append("# Desktop Screens: settings for this server (in singleplayer, for your worlds).\n");
        for (Setting s : SETTINGS) {
            if (p.containsKey(s.key())) continue;
            if (s.comment() != null) text.append("# ").append(s.comment()).append('\n');
            text.append(s.key()).append('=').append(s.value()).append('\n');
        }
        if (text.isEmpty()) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            DesktopScreens.LOG.warn("Couldn't write {}", file, e);
        }
    }

    private static String value(Properties p, String key) {
        String v = p.getProperty(key);
        if (v != null) return v.trim();
        for (Setting s : SETTINGS) {
            if (s.key().equals(key)) return s.value();
        }
        throw new IllegalArgumentException(key);
    }

    private static int intValue(Properties p, String key, int min, int max) {
        int v;
        try {
            v = Integer.parseInt(value(p, key));
        } catch (NumberFormatException e) {
            v = Integer.parseInt(value(new Properties(), key));
        }
        return Mth.clamp(v, min, max);
    }
}
