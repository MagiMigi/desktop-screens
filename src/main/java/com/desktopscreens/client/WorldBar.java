package com.desktopscreens.client;

import com.desktopscreens.core.CommandBar;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
//? if >=26.1 {
/*import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.LightCoordsUtil;
*///?} else {
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
//?}
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * The VR command bar of a screen pointed at in the world ({@link WorldPointing}): the panel's buttons ({@link
 * DesktopScreen}) as labels on solid dark plates, floating under the screen in the glass's plane (over it when the
 * screen stands on the floor), with a line under them for the hint and the commands' messages. Only this player sees
 * it. While picking a window, what to do and Cancel instead, and a frame on the picture around the window under the
 * laser. Positions are in blocks on the whole screen (columns right, rows up), as
 * {@link ScreenGroups.Group#pictureRect} gives them. Game thread.
 * <p>
 * The plates are solid, drawn like the desktop picture, with the text just in front: see-through plates went under
 * the letters or over them by distance (Test 58), were painted over by water drawn later (58b), and on 26.3, which
 * blends everything see-through regardless of order, turned the text grey (60b). So it isn't seen through the floor
 * any more; a screen standing on one has the bar over it instead.
 */
final class WorldBar {
    /**
     * Blocks per font unit at least: the bar is as wide as the screen, so it's read from as far as the screen is, but no
     * smaller than this on a small one, readable from a block or two (a 1-block screen gets a bar 1.6 blocks wide).
     */
    private static final float MIN_SCALE = 1 / 256f;
    /**
     * In font units: between the screen and the bar, between two buttons, between the bar and the line under it, and
     * between that line's lines (it wraps to the bar's width).
     */
    private static final float GAP_ABOVE = 3, GAP = 4, GAP_BELOW = 3, LINE_GAP = 1;
    /** A plate is its text's box and one unit around it, as name tags draw it: 10 units high, one more to the left. */
    private static final float ROW = 10, PLATE_LEFT = 1;
    /** How far (font units) a plate sits behind its text. */
    private static final float PLATE_DEPTH = 0.5f;
    /** The plates' greys (0-255): as dark as the bar on the panel, lighter under the laser. */
    private static final int PLATE = 0x16, PLATE_HOVER = 0x50;
    private static final int TEXT = 0xFFFFFF, TEXT_HOVER = 0xFFFFA0;
    /** Between two hints on the line under the bar (the panel's spacing); a line breaks only between them. */
    private static final String HINT_GAP = "    ";
    /** The frame around the window to pick, in the hints' key color, and how thick it is (a share of the picture's width). */
    private static final int OUTLINE = 0xFFFFD866;
    private static final float OUTLINE_SHARE = 1 / 300f;

    enum Pick { NONE, PIP, WINDOW }

    private Pick picking = Pick.NONE;
    private Component flash;
    private long flashUntil, hintUntil;
    /** The item under the laser last frame, or -1. */
    private int hover = -1;
    /** While picking: the window under the laser, {x, y, w, h} as shares of the picture (rows down), or null. */
    private double[] outline;

    Pick picking() {
        return picking;
    }

    void setPicking(Pick pick) {
        picking = pick;
        outline = null;
    }

    void flash(Component text) {
        flash = text;
        flashUntil = Util.getMillis() + DesktopScreen.FLASH_MILLIS;
    }

    void showHint() {
        hintUntil = Util.getMillis() + DesktopScreen.HINT_MILLIS;
    }

    void setHover(int item) {
        hover = item;
    }

    void setOutline(double[] shares) {
        outline = shares;
    }

    /** The bar's items: the five buttons, or while picking what to do and Cancel (the text first, not a button). */
    List<Component> items(DesktopSession session) {
        if (picking != Pick.NONE) {
            return List.of(Component.translatable(picking == Pick.PIP ? "desktopscreens.bar.pick_pip" : "desktopscreens.bar.pick_window"),
                    Component.translatable("desktopscreens.bar.cancel"));
        }
        return List.of(
                Component.translatable("desktopscreens.bar.pip"),
                Component.translatable(session.window() != null ? "desktopscreens.bar.whole_monitor" : "desktopscreens.bar.one_window"),
                Component.translatable("desktopscreens.bar.next_monitor"),
                Component.translatable("desktopscreens.bar.settings"),
                Component.translatable("desktopscreens.bar.back"));
    }

    /** Whether the item is a button: all of them, but the text while picking. */
    boolean isButton(int item) {
        return item >= 0 && (picking == Pick.NONE || item == 1);
    }

    /**
     * Where the bar goes this frame: centered under the screen and as wide as it (no smaller than {@link #MIN_SCALE}
     * allows); over it instead when {@code above} (the blocks under it are solid, a screen standing on the floor), the
     * line on top then. By the screen, not its picture, so it stays put when a narrow window shows; as wide as the
     * screen, so a big one, watched from farther away, gets big text (Test 60, 2026-10-30: tiny over a big
     * screen when it was at most 6.4 blocks wide).
     */
    static Layout layout(Font font, ScreenGroups.Group screen, List<Component> items, boolean above) {
        double[] widths = new double[items.size()];
        double units = GAP * (items.size() - 1);
        for (int i = 0; i < widths.length; i++) {
            widths[i] = font.width(padded(items.get(i))) + PLATE_LEFT;
            units += widths[i];
        }
        float scale = Math.max(MIN_SCALE, (float) (screen.cols / units));
        for (int i = 0; i < widths.length; i++) widths[i] *= scale;
        float width = (float) (units * scale), left = screen.minCol + (screen.cols - width) / 2;
        float top = above ? screen.minRow + screen.rows + (GAP_ABOVE + ROW) * scale : screen.minRow - GAP_ABOVE * scale;
        return new Layout(items, new CommandBar(widths, left, width, GAP * scale), scale, left, top, width, above);
    }

    /** A button's text with a space on each side, so its plate leaves room around it. */
    private static Component padded(Component label) {
        return Component.literal(" ").append(label).append(" ");
    }

    /** The bar by the screen: its items, where they are, the font units' size in blocks, and whether it's over the screen. */
    record Layout(List<Component> items, CommandBar bar, float scale, float left, float top, float width, boolean above) {
        /** The item at a point of the glass (blocks on the whole screen), or -1 off the bar. */
        int at(double col, double row) {
            if (row > top || row < top - ROW * scale) return -1;
            return bar.at(col);
        }
    }

    /**
     * What one frame draws, taken from the world first (26.1 draws later). {@code items}: the bar's buttons too; not for
     * a screen's hint before it's used ({@link #hintFrame}), which is the line alone.
     */
    record Frame(Layout layout, boolean items, int hover, boolean[] buttons, List<Component> line, int lineAlpha, double[] outline, float[] rect) {}

    /** This frame's drawing for the bar by {@code screen} ({@code above} it, see {@link #layout}), whose picture is at {@code rect}. */
    Frame frame(Font font, DesktopSession session, ScreenGroups.Group screen, boolean above, float[] rect) {
        List<Component> items = items(session);
        boolean[] buttons = new boolean[items.size()];
        for (int i = 0; i < buttons.length; i++) buttons[i] = isButton(i);
        long now = Util.getMillis();
        List<Component> line = null;
        int alpha = 0;
        if (now < flashUntil + DesktopScreen.FADE_MILLIS) {
            line = List.of(flash);
            alpha = fade(now, flashUntil);
        } else if (now < hintUntil + DesktopScreen.FADE_MILLIS) {
            line = List.of(hint("desktopscreens.hint.vr_click", "desktopscreens.key.vr_trigger"),
                    hint("desktopscreens.hint.vr_right_click", "desktopscreens.key.vr_use"),
                    hint("desktopscreens.hint.vr_scroll", "desktopscreens.key.vr_stick"),
                    hint("desktopscreens.hint.vr_back", "desktopscreens.key.vr_menu"),
                    hint("desktopscreens.hint.vr_type", "desktopscreens.key.vr_keyboard"));
            alpha = fade(now, hintUntil);
        }
        return new Frame(layout(font, screen, items, above), true, hover, buttons, line, alpha, picking != Pick.NONE ? outline : null, rect);
    }

    /**
     * A screen's hint at the screen, in VR with controllers and with the test switch ({@link ScreenAim#hintFrame}): only
     * {@code line}, as opaque as {@code alpha}, where the bar's line goes while the screen is used, so the two take turns
     * in one place.
     */
    static Frame hintFrame(Font font, ScreenGroups.Group screen, boolean above, List<Component> line, int alpha) {
        List<Component> items = List.of(
                Component.translatable("desktopscreens.bar.pip"),
                Component.translatable("desktopscreens.bar.one_window"),
                Component.translatable("desktopscreens.bar.next_monitor"),
                Component.translatable("desktopscreens.bar.settings"),
                Component.translatable("desktopscreens.bar.back"));
        return new Frame(layout(font, screen, items, above), false, -1, new boolean[items.size()], line, alpha, null, null);
    }

    /** One hint, "[Trigger] Click": the button's name in the key color, then what it does. */
    private static Component hint(String langKey, String buttonKey) {
        return Component.translatable(langKey, DesktopClient.key(Component.translatable(buttonKey)));
    }

    /**
     * Hints (or a message) in lines no wider than {@code maxWidth}, breaking only between two hints: wrapped at any
     * space, "[Menu button]" was split over two lines (Test 60b). A single hint wider than that wraps by itself.
     */
    private static List<FormattedCharSequence> lines(Font font, List<Component> parts, int maxWidth) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        MutableComponent line = null;
        for (Component part : parts) {
            if (line != null && font.width(line) + font.width(HINT_GAP) + font.width(part) <= maxWidth) {
                line.append(HINT_GAP).append(part);
                continue;
            }
            if (line != null) lines.addAll(font.split(line, maxWidth));
            line = Component.empty().append(part);
        }
        if (line != null) lines.addAll(font.split(line, maxWidth));
        return lines;
    }

    /** 255 while {@code until} is still ahead, then down to 0 over the fade, as the desktop view's banners. */
    private static int fade(long now, long until) {
        return now < until ? 255 : (int) Math.max(0, 255 - (now - until) * 255 / DesktopScreen.FADE_MILLIS);
    }

    /**
     * Draws {@code f} from the block at {@code col, row} of the screen, in its model's coordinates ({@code pose} turned
     * the model's way, see {@link ScreenRenderer}), {@code z} just in front of the glass.
     */
    //? if >=26.1 {
    /*static void draw(Frame f, Font font, PoseStack pose, SubmitNodeCollector out, int col, int row, float z) {
    *///?} else {
    static void draw(Frame f, Font font, PoseStack pose, MultiBufferSource out, int col, int row, float z) {
    //?}
        if (f.outline() != null) {
            // Solid, as the plates: drawn as debug quads, 26.3 left it out (Test 67, 2026-11-01).
            //? if >=26.1 {
            /*out.submitCustomGeometry(pose, SolidColor.ofRgb(Minecraft.getInstance(), "bar_outline", OUTLINE & 0xFFFFFF),
                    (at, quads) -> drawOutline(f, at.pose(), quads, col, row, z));
            *///?} else {
            drawOutline(f, pose.last().pose(), out.getBuffer(SolidColor.ofRgb(Minecraft.getInstance(), "bar_outline", OUTLINE & 0xFFFFFF)),
                    col, row, z);
            //?}
        }
        Layout l = f.layout();
        float s = l.scale();
        pose.pushPose();
        pose.translate(1 - (l.left() - col), l.top() - row, z); // the bar's top left corner, as the viewer sees it
        // As signs do it: their text faces the one looking at them from higher z, with x to the right.
        pose.mulPose(Axis.YP.rotationDegrees(180));
        pose.scale(s, -s, s);
        for (int i = 0; f.items() && i < l.items().size(); i++) {
            boolean hovered = f.buttons()[i] && i == f.hover();
            float x = (float) ((l.bar().x(i) - l.left()) / s) + PLATE_LEFT;
            text(font, pose, out, padded(l.items().get(i)).getVisualOrderText(), x, PLATE_LEFT, 0xFF000000 | (hovered ? TEXT_HOVER : TEXT),
                    hovered ? PLATE_HOVER : PLATE);
        }
        if (f.line() != null && f.lineAlpha() >= 4) { // Minecraft's font drew alpha below 4 fully opaque
            // Wrapped to the bar's width (the hint is wider than the bar), under the bar, or over it when it's over the screen.
            List<FormattedCharSequence> lines = lines(font, f.line(), (int) (l.width() / s));
            float height = lines.size() * (ROW + LINE_GAP) - LINE_GAP;
            float top = l.above() ? -(GAP_BELOW + height) : ROW + GAP_BELOW;
            for (int i = 0; i < lines.size(); i++) {
                FormattedCharSequence line = FormattedCharSequence.composite(SPACE, lines.get(i), SPACE);
                float x = (l.width() / s - font.width(line)) / 2;
                text(font, pose, out, line, x, top + i * (ROW + LINE_GAP) + PLATE_LEFT, f.lineAlpha() << 24 | TEXT, PLATE);
            }
        }
        pose.popPose();
    }

    private static final FormattedCharSequence SPACE = FormattedCharSequence.forward(" ", Style.EMPTY);

    /**
     * Text at {@code x, y} (font units, rows down) on its plate, a solid grey ({@code grey}) just behind it. Both pulled
     * toward the viewer alike, as the desktop picture is (the cover's note does the same): with the text not pulled,
     * the plate's pull put it in front of the letters from afar or at a slant (Test 60c, 2026-10-30).
     */
    //? if >=26.1 {
    /*private static void text(Font font, PoseStack pose, SubmitNodeCollector out, FormattedCharSequence text, float x, float y, int argb, int grey) {
        float w = font.width(text);
        out.submitCustomGeometry(pose, SolidColor.of(Minecraft.getInstance(), plateName(grey), grey), (at, quads) -> plate(at.pose(), quads, x, y, w));
        out.submitText(pose, x, y, text, false, Font.DisplayMode.POLYGON_OFFSET, LightCoordsUtil.FULL_BRIGHT, argb, 0, 0);
    }
    *///?} else {
    private static void text(Font font, PoseStack pose, MultiBufferSource out, FormattedCharSequence text, float x, float y, int argb, int grey) {
        Matrix4f m = pose.last().pose();
        plate(m, out.getBuffer(SolidColor.of(Minecraft.getInstance(), plateName(grey), grey)), x, y, font.width(text));
        font.drawInBatch(text, x, y, argb, false, m, out, Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
    }
    //?}

    private static String plateName(int grey) {
        return "bar_plate_" + Integer.toHexString(grey);
    }

    /**
     * The plate behind text at {@code x, y} (font units, rows down) that is {@code w} wide: one unit around it, as the
     * font's own background would be. Both sides, as the picture's drawing shows only the side facing it.
     */
    private static void plate(Matrix4f m, VertexConsumer out, float x, float y, float w) {
        // Behind the text (toward the screen's back here), by less than a hair at any scale.
        float z = -PLATE_DEPTH, x0 = x - PLATE_LEFT, x1 = x + w, y0 = y - 1, y1 = y + ROW - 1;
        Vertices.posUv(out, m, x0, y0, z, 0.5f, 0.5f);
        Vertices.posUv(out, m, x0, y1, z, 0.5f, 0.5f);
        Vertices.posUv(out, m, x1, y1, z, 0.5f, 0.5f);
        Vertices.posUv(out, m, x1, y0, z, 0.5f, 0.5f);
        Vertices.posUv(out, m, x1, y0, z, 0.5f, 0.5f);
        Vertices.posUv(out, m, x1, y1, z, 0.5f, 0.5f);
        Vertices.posUv(out, m, x0, y1, z, 0.5f, 0.5f);
        Vertices.posUv(out, m, x0, y0, z, 0.5f, 0.5f);
    }

    /** The frame around the window to pick, on the picture, from the block at {@code col, row} (in its model's coordinates). */
    private static void drawOutline(Frame f, Matrix4f m, VertexConsumer out, int col, int row, float z) {
        float[] rect = f.rect();
        double[] o = f.outline();
        float x0 = (float) (rect[0] + o[0] * rect[2]), x1 = (float) (rect[0] + (o[0] + o[2]) * rect[2]);
        float y1 = (float) (rect[1] + rect[3] - o[1] * rect[3]), y0 = (float) (rect[1] + rect[3] - (o[1] + o[3]) * rect[3]);
        float t = rect[2] * OUTLINE_SHARE;
        box(m, out, col, row, x0, x1, y1 - t, y1, z);
        box(m, out, col, row, x0, x1, y0, y0 + t, z);
        box(m, out, col, row, x0, x0 + t, y0, y1, z);
        box(m, out, col, row, x1 - t, x1, y0, y1, z);
    }

    /** A filled box {x0, x1, y0, y1} on the whole screen, relative to the block at {@code col, row}. */
    private static void box(Matrix4f m, VertexConsumer out, int col, int row, float x0, float x1, float y0, float y1, float z) {
        float xLeft = 1 - (x0 - col), xRight = 1 - (x1 - col), yBottom = y0 - row, yTop = y1 - row;
        // Both sides, as the plates: the picture's drawing shows only the side facing it.
        Vertices.posUv(out, m, xLeft, yBottom, z, 0.5f, 0.5f);
        Vertices.posUv(out, m, xRight, yBottom, z, 0.5f, 0.5f);
        Vertices.posUv(out, m, xRight, yTop, z, 0.5f, 0.5f);
        Vertices.posUv(out, m, xLeft, yTop, z, 0.5f, 0.5f);
        Vertices.posUv(out, m, xLeft, yTop, z, 0.5f, 0.5f);
        Vertices.posUv(out, m, xRight, yTop, z, 0.5f, 0.5f);
        Vertices.posUv(out, m, xRight, yBottom, z, 0.5f, 0.5f);
        Vertices.posUv(out, m, xLeft, yBottom, z, 0.5f, 0.5f);
    }
}
