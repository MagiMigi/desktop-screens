package com.desktopscreens.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/** Draws the configured frame around the desktop picture. */
final class BorderRenderer {
    /** Rainbow edges are split into pieces about this long, each with its own colors at both ends. */
    private static final float RAINBOW_STEP = 12;

    private BorderRenderer() {}

    /** How much room the border needs on each side of the picture. */
    static int thickness() {
        return DesktopConfig.borderStyle == BorderStyle.NONE ? 0 : DesktopConfig.borderThickness;
    }

    /** Draws the frame just outside the rectangle, in the current pose's units. */
    static void draw(GuiGraphics g, int x, int y, int w, int h) {
        int t = thickness();
        if (t <= 0) return;
        if (DesktopConfig.borderStyle == BorderStyle.RAINBOW) {
            drawRainbow(g.pose().last().pose(), x - t, y - t, w + 2 * t, h + 2 * t, t);
            return;
        }
        int color = 0xFF000000 | DesktopConfig.borderColor;
        g.fill(x - t, y - t, x + w + t, y, color);         // top, with corners
        g.fill(x - t, y + h, x + w + t, y + h + t, color); // bottom, with corners
        g.fill(x - t, y, x, y + h, color);                 // left
        g.fill(x + w, y, x + w + t, y + h, color);         // right
    }

    /** A ring of hue gradients. Colors follow the distance around the frame, shifted over time so the rainbow flows. */
    private static void drawRainbow(Matrix4f pose, float x, float y, float w, float h, float t) {
        float perimeter = 2 * (w + h);
        long loopMillis = DesktopConfig.rainbowSeconds * 1000L;
        float shift = (Util.getMillis() % loopMillis) / (float) loopMillis;
        Rainbow rainbow = new Rainbow(perimeter, shift);

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        // Clockwise from the top-left corner. Distances along the frame: top 0..w, right w..w+h, bottom, then left.
        for (float[] s : pieces(x, x + w)) { // top, left to right
            horizontal(buffer, pose, s[0], s[1], y, y + t, rainbow.at(s[0] - x), rainbow.at(s[1] - x));
        }
        for (float[] s : pieces(y + t, y + h - t)) { // right, top to bottom
            vertical(buffer, pose, x + w - t, x + w, s[0], s[1], rainbow.at(w + s[0] - y), rainbow.at(w + s[1] - y));
        }
        for (float[] s : pieces(x, x + w)) { // bottom, right to left
            horizontal(buffer, pose, s[0], s[1], y + h - t, y + h, rainbow.at(w + h + (x + w - s[0])), rainbow.at(w + h + (x + w - s[1])));
        }
        for (float[] s : pieces(y + t, y + h - t)) { // left, bottom to top
            vertical(buffer, pose, x, x + t, s[0], s[1], rainbow.at(2 * w + h + (y + h - s[0])), rainbow.at(2 * w + h + (y + h - s[1])));
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    /** Splits [from, to] into pieces about RAINBOW_STEP long. */
    private static float[][] pieces(float from, float to) {
        int n = Math.max(1, Mth.ceil((to - from) / RAINBOW_STEP));
        float[][] out = new float[n][];
        for (int i = 0; i < n; i++) {
            out[i] = new float[] {from + (to - from) * i / n, from + (to - from) * (i + 1) / n};
        }
        return out;
    }

    /** A horizontal band with color {@code left} at x1 blending to {@code right} at x2. */
    private static void horizontal(BufferBuilder b, Matrix4f pose, float x1, float x2, float y1, float y2, int left, int right) {
        b.addVertex(pose, x1, y1, 0).setColor(left);
        b.addVertex(pose, x1, y2, 0).setColor(left);
        b.addVertex(pose, x2, y2, 0).setColor(right);
        b.addVertex(pose, x2, y1, 0).setColor(right);
    }

    /** A vertical band with color {@code top} at y1 blending to {@code bottom} at y2. */
    private static void vertical(BufferBuilder b, Matrix4f pose, float x1, float x2, float y1, float y2, int top, int bottom) {
        b.addVertex(pose, x1, y1, 0).setColor(top);
        b.addVertex(pose, x1, y2, 0).setColor(bottom);
        b.addVertex(pose, x2, y2, 0).setColor(bottom);
        b.addVertex(pose, x2, y1, 0).setColor(top);
    }

    private record Rainbow(float perimeter, float shift) {
        /** ARGB color at a distance along the frame. */
        int at(float distance) {
            float hue = distance / perimeter - shift; // minus: the colors flow clockwise
            hue -= Mth.floor(hue);
            return 0xFF000000 | Mth.hsvToRgb(hue, 0.85f, 1f);
        }
    }
}
