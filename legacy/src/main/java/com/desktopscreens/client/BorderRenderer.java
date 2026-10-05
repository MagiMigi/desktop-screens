package com.desktopscreens.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.opengl.GL11;

/** Draws the configured frame around the desktop picture. */
final class BorderRenderer {
    /** Rainbow edges are split into pieces about this long, each with its own colors at both ends. */
    private static final float RAINBOW_STEP = 12;

    private BorderRenderer() {}

    /** How much room the border needs on each side of the picture. */
    static int thickness() {
        return DesktopConfig.borderStyle == BorderStyle.NONE ? 0 : DesktopConfig.borderThickness;
    }

    /** Draws the frame just outside the rectangle, in the current units. */
    static void draw(int x, int y, int w, int h) {
        int t = thickness();
        if (t <= 0) return;
        if (DesktopConfig.borderStyle == BorderStyle.RAINBOW) {
            drawRainbow(x - t, y - t, w + 2 * t, h + 2 * t, t);
            return;
        }
        int color = 0xFF000000 | DesktopConfig.borderColor;
        Gui.drawRect(x - t, y - t, x + w + t, y, color);         // top, with corners
        Gui.drawRect(x - t, y + h, x + w + t, y + h + t, color); // bottom, with corners
        Gui.drawRect(x - t, y, x, y + h, color);                 // left
        Gui.drawRect(x + w, y, x + w + t, y + h, color);         // right
    }

    private static void drawRainbow(float x, float y, float w, float h, float t) {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        drawRainbow((vx, vy, argb) -> buffer.pos(vx, vy, 0)
                .color(argb >> 16 & 0xFF, argb >> 8 & 0xFF, argb & 0xFF, argb >>> 24).endVertex(), x, y, w, h, t);
        tessellator.draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
    }

    /** Where the ring's corners go: one vertex at a time, four to a quad. */
    private interface Corners {
        void add(float x, float y, int argb);
    }

    /** A ring of hue gradients. Colors follow the distance around the frame, shifted over time so the rainbow flows. */
    private static void drawRainbow(Corners out, float x, float y, float w, float h, float t) {
        float perimeter = 2 * (w + h);
        long loopMillis = DesktopConfig.rainbowSeconds * 1000L;
        float shift = (Minecraft.getSystemTime() % loopMillis) / (float) loopMillis;
        Rainbow rainbow = new Rainbow(perimeter, shift);

        // Clockwise from the top-left corner. Distances along the frame: top 0..w, right w..w+h, bottom, then left.
        for (float[] s : pieces(x, x + w)) { // top, left to right
            horizontal(out, s[0], s[1], y, y + t, rainbow.at(s[0] - x), rainbow.at(s[1] - x));
        }
        for (float[] s : pieces(y + t, y + h - t)) { // right, top to bottom
            vertical(out, x + w - t, x + w, s[0], s[1], rainbow.at(w + s[0] - y), rainbow.at(w + s[1] - y));
        }
        for (float[] s : pieces(x, x + w)) { // bottom, right to left
            horizontal(out, s[0], s[1], y + h - t, y + h, rainbow.at(w + h + (x + w - s[0])), rainbow.at(w + h + (x + w - s[1])));
        }
        for (float[] s : pieces(y + t, y + h - t)) { // left, bottom to top
            vertical(out, x, x + t, s[0], s[1], rainbow.at(2 * w + h + (y + h - s[0])), rainbow.at(2 * w + h + (y + h - s[1])));
        }
    }

    /** Splits [from, to] into pieces about RAINBOW_STEP long. */
    private static float[][] pieces(float from, float to) {
        int n = Math.max(1, MathHelper.ceil((to - from) / RAINBOW_STEP));
        float[][] out = new float[n][];
        for (int i = 0; i < n; i++) {
            out[i] = new float[] {from + (to - from) * i / n, from + (to - from) * (i + 1) / n};
        }
        return out;
    }

    /** A horizontal band with color {@code left} at x1 blending to {@code right} at x2. */
    private static void horizontal(Corners out, float x1, float x2, float y1, float y2, int left, int right) {
        out.add(x1, y1, left);
        out.add(x1, y2, left);
        out.add(x2, y2, right);
        out.add(x2, y1, right);
    }

    /** A vertical band with color {@code top} at y1 blending to {@code bottom} at y2. */
    private static void vertical(Corners out, float x1, float x2, float y1, float y2, int top, int bottom) {
        out.add(x1, y1, top);
        out.add(x1, y2, bottom);
        out.add(x2, y2, bottom);
        out.add(x2, y1, top);
    }

    private static final class Rainbow {
        private final float perimeter, shift;

        Rainbow(float perimeter, float shift) {
            this.perimeter = perimeter;
            this.shift = shift;
        }

        /** ARGB color at a distance along the frame. */
        int at(float distance) {
            float hue = distance / perimeter - shift; // minus: the colors flow clockwise
            hue -= MathHelper.floor(hue);
            return 0xFF000000 | MathHelper.hsvToRGB(hue, 0.85f, 1f);
        }
    }
}
