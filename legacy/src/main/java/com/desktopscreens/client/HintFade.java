package com.desktopscreens.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.text.ITextComponent;

import java.util.List;

/**
 * A hint under the crosshair that shows for a few seconds when it appears, then fades out: hints that stayed while you
 * looked at a screen were annoying. After a while without it, or for another screen, it shows again. Keyed by what
 * it's about, so looking from one screen to another shows the new one's.
 */
final class HintFade {
    private static final long SHOW_MILLIS = 3000, FADE_MILLIS = 500, REARM_MILLIS = 2000;

    private String key;
    private long since, lastSeen;

    /** Draws the lines centered at x, y, one under the other, as opaque as they are by now. Call every frame they would show. False once faded out. */
    boolean draw(FontRenderer font, String hintKey, List<ITextComponent> lines, int x, int y, int rgb) {
        long now = Minecraft.getSystemTime();
        if (!hintKey.equals(key) || now - lastSeen > REARM_MILLIS) {
            key = hintKey;
            since = now;
        }
        lastSeen = now;
        long age = now - since;
        int alpha = age < SHOW_MILLIS ? 255 : (int) Math.max(0, 255 - (age - SHOW_MILLIS) * 255 / FADE_MILLIS);
        if (alpha < 4) return false; // Minecraft's font draws alpha below 4 fully opaque
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).getFormattedText();
            font.drawStringWithShadow(line, x - font.getStringWidth(line) / 2f, y + i * 12, alpha << 24 | rgb);
        }
        GlStateManager.disableBlend();
        return true;
    }

    /** Looked away: the next {@code draw} shows the hint again at once, without the wait after a while without it. */
    void reset() {
        key = null;
    }
}
