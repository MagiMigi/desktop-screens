package com.desktopscreens.client;

//? if >=26.1 {
/*import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
*///?} else {
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
//?}
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

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
            //? if >=26.1 {
            /*GuiDraw.submit(g, new Rainbow26(new Matrix3x2f(g.pose()), x - t, y - t, w + 2 * t, h + 2 * t, t, GuiDraw.scissor(g)));
            *///?} else {
            drawRainbow(g.pose().last().pose(), x - t, y - t, w + 2 * t, h + 2 * t, t);
            //?}
            return;
        }
        int color = 0xFF000000 | DesktopConfig.borderColor;
        g.fill(x - t, y - t, x + w + t, y, color);         // top, with corners
        g.fill(x - t, y + h, x + w + t, y + h + t, color); // bottom, with corners
        g.fill(x - t, y, x, y + h, color);                 // left
        g.fill(x + w, y, x + w + t, y + h, color);         // right
    }

    //? if >=26.1 {
    /*/^* The rainbow ring for 26.1's GUI, which draws everything later, in batches: its quads, built when that happens. ^/
    private record Rainbow26(Matrix3x2f pose, float x, float y, float w, float h, float t, ScreenRectangle scissorArea,
                             ScreenRectangle bounds) implements GuiElementRenderState {
        Rainbow26(Matrix3x2f pose, float x, float y, float w, float h, float t, ScreenRectangle scissorArea) {
            this(pose, x, y, w, h, t, scissorArea, bounds(pose, x, y, w, h, scissorArea));
        }

        private static ScreenRectangle bounds(Matrix3x2f pose, float x, float y, float w, float h, ScreenRectangle scissorArea) {
            ScreenRectangle r = new ScreenRectangle(Mth.floor(x), Mth.floor(y), Mth.ceil(w), Mth.ceil(h)).transformMaxBounds(pose);
            return scissorArea != null ? scissorArea.intersection(r) : r;
        }

        @Override
        public void buildVertices(VertexConsumer out) {
            drawRainbow((vx, vy, color) -> out.addVertexWith2DPose(pose, vx, vy).setColor(color), x, y, w, h, t);
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }
    }
    *///?} else {
    private static void drawRainbow(Matrix4f pose, float x, float y, float w, float h, float t) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buffer = Vertices.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        drawRainbow((vx, vy, color) -> Vertices.posColor(buffer, pose, vx, vy, 0, color), x, y, w, h, t);
        Vertices.draw(buffer);
    }
    //?}

    /** Where the ring's corners go: one vertex at a time, four to a quad. */
    private interface Corners {
        void add(float x, float y, int argb);
    }

    /** A ring of hue gradients. Colors follow the distance around the frame, shifted over time so the rainbow flows. */
    private static void drawRainbow(Corners out, float x, float y, float w, float h, float t) {
        float perimeter = 2 * (w + h);
        long loopMillis = DesktopConfig.rainbowSeconds * 1000L;
        float shift = (Util.getMillis() % loopMillis) / (float) loopMillis;
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
        int n = Math.max(1, Mth.ceil((to - from) / RAINBOW_STEP));
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

    private record Rainbow(float perimeter, float shift) {
        /** ARGB color at a distance along the frame. */
        int at(float distance) {
            float hue = distance / perimeter - shift; // minus: the colors flow clockwise
            hue -= Mth.floor(hue);
            return 0xFF000000 | Mth.hsvToRgb(hue, 0.85f, 1f);
        }
    }
}
