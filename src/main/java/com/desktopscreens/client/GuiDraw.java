package com.desktopscreens.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
//? if >=26.1 {
/*import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
//? if fabric {
/^import com.desktopscreens.fabric.mixin.GuiStateAccessor;
^///?}
*///?}
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * GUI calls that differ between Minecraft versions, in one place: 26.1 places things with a 2D pose (no z), and
 * names the text calls differently.
 */
final class GuiDraw {
    private GuiDraw() {}

    static void push(GuiGraphics g) {
        //? if >=1.21.6 {
        /*g.pose().pushMatrix();
        *///?} else {
        g.pose().pushPose();
        //?}
    }

    static void pop(GuiGraphics g) {
        //? if >=1.21.6 {
        /*g.pose().popMatrix();
        *///?} else {
        g.pose().popPose();
        //?}
    }

    static void scale(GuiGraphics g, float x, float y) {
        //? if >=1.21.6 {
        /*g.pose().scale(x, y);
        *///?} else {
        g.pose().scale(x, y, 1);
        //?}
    }

    static void translate(GuiGraphics g, float x, float y) {
        //? if >=1.21.6 {
        /*g.pose().translate(x, y);
        *///?} else {
        g.pose().translate(x, y, 0);
        //?}
    }

    static void text(GuiGraphics g, Font font, Component text, int x, int y, int color) {
        text(g, font, text.getVisualOrderText(), x, y, color, true);
    }

    static void text(GuiGraphics g, Font font, Component text, int x, int y, int color, boolean shadow) {
        text(g, font, text.getVisualOrderText(), x, y, color, shadow);
    }

    static void text(GuiGraphics g, Font font, String text, int x, int y, int color) {
        text(g, font, Component.literal(text).getVisualOrderText(), x, y, color, true);
    }

    static void text(GuiGraphics g, Font font, FormattedCharSequence text, int x, int y, int color) {
        text(g, font, text, x, y, color, true);
    }

    static void text(GuiGraphics g, Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        //? if >=26.1 {
        /*g.text(font, text, x, y, opaque(color), shadow);
        *///?} else {
        g.drawString(font, text, x, y, color, shadow);
        //?}
    }

    //? if >=26.1 {
    /*/^* Adds one of our own pieces to what the GUI draws this frame, in order with the rest. ^/
    static void submit(GuiGraphics g, GuiElementRenderState element) {
        //? if neoforge {
        g.submitGuiElementRenderState(element);
        //?} else {
        /^((GuiStateAccessor) g).desktopscreens$guiRenderState().addGuiElement(element);
        ^///?}
    }

    /^*
     * The area drawing is cut to right now, or null. Only NeoForge says (vanilla keeps it to itself); our pictures
     * are never drawn inside a cut area (scrolled lists), so on Fabric they simply aren't cut.
     ^/
    static ScreenRectangle scissor(GuiGraphics g) {
        //? if neoforge {
        return g.peekScissorStack();
        //?} else {
        /^return null;
        ^///?}
    }
    *///?}

    /** What was drawn so far goes under what comes next. 26.1 sorts that out by itself: overlapping things keep their order. */
    static void flush(GuiGraphics g) {
        //? if <26.1 {
        g.flush();
        //?}
    }

    static void centeredText(GuiGraphics g, Font font, Component text, int x, int y, int color) {
        FormattedCharSequence line = text.getVisualOrderText();
        text(g, font, line, x - font.width(line) / 2, y, color, true);
    }

    /**
     * Colors without alpha (0xRRGGBB) draw opaque, as Minecraft's text did before 1.21.6, where an alpha of 0 now
     * hides the text: anything under 4 counts as none, the old rule.
     */
    private static int opaque(int color) {
        return (color & 0xFC000000) == 0 ? color | 0xFF000000 : color;
    }
}
