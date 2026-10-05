package com.desktopscreens.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;

import java.util.Arrays;
import java.util.List;

/**
 * Where a picture of the monitor Minecraft is on shows the game itself, because the game isn't hidden from screen
 * capture ({@link DesktopConfig#hideGame}, off by default): a dark cover with a note on how to see the desktop there
 * instead, rather than the game inside itself, over and over. In the settings preview, on screens in the world and in
 * picture-in-picture.
 */
final class GameCover {
    private static final int GREY = 0x16, TITLE = 0xFFFFFFFF, TEXT = 0xFFB0B0B0;
    /** Font units: between lines, and more after the first. */
    private static final int LINE_GAP = 2, TITLE_GAP = 5;

    private GameCover() {}

    private static List<ITextComponent> lines() {
        return Arrays.asList(new TextComponentTranslation("desktopscreens.cover.title"),
                new TextComponentTranslation("desktopscreens.cover.how", DesktopClient.hostKey("O")),
                new TextComponentTranslation("desktopscreens.cover.why"));
    }

    /**
     * Where the game ({@code game}, picture pixels, from {@link LiveCapture#gameArea}) lands when the {@code src} part
     * of the picture is drawn into the rectangle {@code x, y, w, h}: {left, top, right, bottom} in its units, cut to
     * it; null if it doesn't show there.
     */
    static int[] place(int[] game, double srcX, double srcY, double srcW, double srcH, int x, int y, int w, int h) {
        if (game == null || srcW <= 0 || srcH <= 0) return null;
        int left = Math.max(x, (int) Math.round(x + (game[0] - srcX) * w / srcW));
        int top = Math.max(y, (int) Math.round(y + (game[1] - srcY) * h / srcH));
        int right = Math.min(x + w, (int) Math.round(x + (game[2] - srcX) * w / srcW));
        int bottom = Math.min(y + h, (int) Math.round(y + (game[3] - srcY) * h / srcH));
        return right > left && bottom > top ? new int[] {left, top, right, bottom} : null;
    }

    /** In the GUI: the cover over {@code r} ({@link #place}), and the note in it, as big as fits. */
    static void drawGui(int[] r) {
        Gui.drawRect(r[0], r[1], r[2], r[3], 0xFF000000 | GREY << 16 | GREY << 8 | GREY);
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        List<ITextComponent> lines = lines();
        float s = scale(font, lines, r[2] - r[0], r[3] - r[1]);
        GlStateManager.pushMatrix();
        GlStateManager.translate((r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f - s * height(font, lines) / 2, 0);
        GlStateManager.scale(s, s, 1);
        int y = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).getFormattedText();
            font.drawString(line, -font.getStringWidth(line) / 2, y, i == 0 ? TITLE : TEXT);
            y += font.FONT_HEIGHT + (i == 0 ? TITLE_GAP : LINE_GAP);
        }
        GlStateManager.popMatrix();
    }

    /** Units per font unit, so the note fills most of a {@code w} x {@code h} cover. */
    private static float scale(FontRenderer font, List<ITextComponent> lines, float w, float h) {
        int widest = 1;
        for (ITextComponent line : lines) widest = Math.max(widest, font.getStringWidth(line.getFormattedText()));
        return Math.min(0.85f * w / widest, 0.8f * h / height(font, lines));
    }

    private static int height(FontRenderer font, List<ITextComponent> lines) {
        return lines.size() * font.FONT_HEIGHT + TITLE_GAP + (lines.size() - 2) * LINE_GAP;
    }
}
