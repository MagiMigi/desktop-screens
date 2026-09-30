package com.desktopscreens.client;

import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * A hint under the crosshair that shows for a few seconds when it appears, then fades out: hints that stayed while you
 * looked at a screen were annoying (2026-09-24). After a while without it, or for another screen, it shows
 * again. Keyed by what it's about, so looking from one screen to another shows the new one's.
 */
final class HintFade {
    private static final long SHOW_MILLIS = 3000, FADE_MILLIS = 500, REARM_MILLIS = 2000;

    private String key;
    private long since, lastSeen;

    /** Draws {@code text} centered at x, y, as opaque as it is by now. Call every frame it would show. False once it has faded out. */
    boolean draw(GuiGraphics g, Font font, String hintKey, Component text, int x, int y, int rgb) {
        return draw(g, font, hintKey, List.of(text), x, y, rgb);
    }

    /** The same with several lines, one under the other. */
    boolean draw(GuiGraphics g, Font font, String hintKey, List<Component> lines, int x, int y, int rgb) {
        long now = Util.getMillis();
        if (!hintKey.equals(key) || now - lastSeen > REARM_MILLIS) {
            key = hintKey;
            since = now;
        }
        lastSeen = now;
        long age = now - since;
        int alpha = age < SHOW_MILLIS ? 255 : (int) Math.max(0, 255 - (age - SHOW_MILLIS) * 255 / FADE_MILLIS);
        if (alpha < 4) return false; // Minecraft's font draws alpha below 4 fully opaque
        for (int i = 0; i < lines.size(); i++) g.drawCenteredString(font, lines.get(i), x, y + i * 12, alpha << 24 | rgb);
        return true;
    }
}
