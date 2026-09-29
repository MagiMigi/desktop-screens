package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Where a picture of the monitor Minecraft is on shows the game itself, because the game isn't hidden from screen
 * capture ({@link DesktopConfig#hideGame}, off by default): a dark cover with a note on how to see the desktop there
 * instead, rather than the game inside itself, over and over. On screens in the world, in picture-in-picture and in
 * the settings preview. The player who sees it is the one who can change it; a streamer whose stream lost the game
 * never would have (2026-09-28).
 */
final class GameCover {
    private static final int GREY = 0x16, TITLE = 0xFFFFFFFF, TEXT = 0xFFB0B0B0;
    private static final ResourceLocation TEXTURE = DesktopScreens.id("game_cover");
    /** Font units: between lines, and more after the first. */
    private static final int LINE_GAP = 2, TITLE_GAP = 5;
    private static RenderType renderType;

    private GameCover() {}

    private static List<Component> lines() {
        return List.of(Component.translatable("desktopscreens.cover.title"),
                Component.translatable("desktopscreens.cover.how", DesktopClient.hostKey("O")),
                Component.translatable("desktopscreens.cover.why"));
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
    static void drawGui(GuiGraphics g, int[] r) {
        g.fill(r[0], r[1], r[2], r[3], 0xFF000000 | GREY << 16 | GREY << 8 | GREY);
        Font font = Minecraft.getInstance().font;
        List<Component> lines = lines();
        float s = scale(font, lines, r[2] - r[0], r[3] - r[1]);
        g.pose().pushPose();
        g.pose().translate((r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f - s * height(font, lines) / 2, 0);
        g.pose().scale(s, s, 1);
        int y = 0;
        for (int i = 0; i < lines.size(); i++) {
            Component line = lines.get(i);
            g.drawString(font, line, -font.width(line) / 2, y, i == 0 ? TITLE : TEXT, false);
            y += font.lineHeight + (i == 0 ? TITLE_GAP : LINE_GAP);
        }
        g.pose().popPose();
    }

    /** For screens in the world: draws in the cover's color (a one-pixel texture, so it's drawn like the picture). */
    static RenderType renderType(Minecraft mc) {
        if (renderType == null) {
            NativeImage pixel = new NativeImage(1, 1, false);
            pixel.setPixelRGBA(0, 0, 0xFF000000 | GREY << 16 | GREY << 8 | GREY); // grey reads the same in any byte order
            mc.getTextureManager().register(TEXTURE, new DynamicTexture(pixel));
            renderType = PictureRenderType.of(TEXTURE);
        }
        return renderType;
    }

    /**
     * For screens in the world, in the model's coordinates of {@link ScreenRenderer} (the viewer looks at it from
     * lower z, their right is lower x, up is higher y): the note in a cover whose top-left corner, seen from the
     * front, is at {@code x, y, z}, {@code w} wide and {@code h} tall, in blocks.
     */
    static void drawWorld(PoseStack pose, MultiBufferSource buffers, float x, float y, float z, float w, float h) {
        Font font = Minecraft.getInstance().font;
        List<Component> lines = lines();
        float s = scale(font, lines, w, h);
        pose.pushPose();
        pose.translate(x, y, z);
        // As signs do it: their text faces the one looking at them from higher z, with x to the right.
        pose.mulPose(Axis.YP.rotationDegrees(180));
        pose.scale(s, -s, s);
        float width = w / s, top = (h / s - height(font, lines)) / 2;
        for (int i = 0; i < lines.size(); i++) {
            Component line = lines.get(i);
            font.drawInBatch(line, (width - font.width(line)) / 2, top, i == 0 ? TITLE : TEXT, false, pose.last().pose(), buffers,
                    Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
            top += font.lineHeight + (i == 0 ? TITLE_GAP : LINE_GAP);
        }
        pose.popPose();
    }

    /** Units per font unit, so the note fills most of a {@code w} x {@code h} cover. */
    private static float scale(Font font, List<Component> lines, float w, float h) {
        int widest = 1;
        for (Component line : lines) widest = Math.max(widest, font.width(line));
        return Math.min(0.85f * w / widest, 0.8f * h / height(font, lines));
    }

    private static int height(Font font, List<Component> lines) {
        return lines.size() * font.lineHeight + TITLE_GAP + (lines.size() - 2) * LINE_GAP;
    }
}
