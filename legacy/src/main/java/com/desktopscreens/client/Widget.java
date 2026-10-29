package com.desktopscreens.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;

import java.util.List;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The settings panel's controls on 1.12.2, which has only plain buttons: each kind the newer versions' panel uses
 * (a button, one that cycles through values, a slider over whole numbers, a color swatch), with a tooltip. The screen
 * calls {@link #onPress} for a click and draws the tooltip of the one under the mouse.
 */
abstract class Widget extends GuiButton {
    private static int nextId;
    /** Shown while the mouse is over it, also while it's greyed out; null for none. */
    ITextComponent tooltip;

    Widget(int x, int y, int width, int height) {
        super(nextId++, x, y, width, height, "");
    }

    /** Clicked (the screen calls it). */
    void onPress() {}

    Widget tooltip(ITextComponent text) {
        tooltip = text;
        return this;
    }

    /** A plain button. */
    static final class Button extends Widget {
        private final Runnable action;

        Button(int x, int y, int width, ITextComponent label, Runnable action) {
            super(x, y, width, 20);
            displayString = label.getFormattedText();
            this.action = action;
        }

        @Override
        void onPress() {
            action.run();
        }

        /**
         * As Minecraft draws a button, but at any height: its picture is made for 20 rows, so a shorter button takes
         * its top half from the picture's top and its bottom half from the picture's bottom, edges and all.
         */
        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
            if (height >= 20) {
                super.drawButton(mc, mouseX, mouseY, partialTicks);
                return;
            }
            if (!visible) return;
            hovered = mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
            mc.getTextureManager().bindTexture(BUTTON_TEXTURES);
            GlStateManager.color(1, 1, 1, 1);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
            int v = 46 + getHoverState(hovered) * 20, top = height / 2, bottom = height - top;
            drawTexturedModalRect(x, y, 0, v, width / 2, top);
            drawTexturedModalRect(x + width / 2, y, 200 - width / 2, v, width / 2, top);
            drawTexturedModalRect(x, y + top, 0, v + 20 - bottom, width / 2, bottom);
            drawTexturedModalRect(x + width / 2, y + top, 200 - width / 2, v + 20 - bottom, width / 2, bottom);
            int color = !enabled ? 0xA0A0A0 : hovered ? 0xFFFFA0 : 0xE0E0E0;
            drawCenteredString(mc.fontRenderer, displayString, x + width / 2, y + (height - 8) / 2, color);
        }
    }

    /**
     * Cycles through values on every click: "Name: value", or only the value without a name. The values are asked for
     * on every click, so they may change.
     */
    static final class Cycle<T> extends Widget {
        private final ITextComponent name;
        private final Supplier<List<T>> values;
        private final Function<T, ITextComponent> label;
        private final java.util.function.Consumer<T> onChange;
        private T value;

        Cycle(int x, int y, int width, ITextComponent name, Supplier<List<T>> values, Function<T, ITextComponent> label, T value,
              java.util.function.Consumer<T> onChange) {
            super(x, y, width, 20);
            this.name = name;
            this.values = values;
            this.label = label;
            this.onChange = onChange;
            set(value);
        }

        /** Shows another value, without telling anyone. */
        void set(T newValue) {
            value = newValue;
            String text = label.apply(value).getFormattedText();
            displayString = name == null ? text : name.getFormattedText() + ": " + text;
        }

        @Override
        void onPress() {
            List<T> list = values.get();
            int at = list.indexOf(value);
            set(list.get((at + 1) % list.size()));
            onChange.accept(value);
        }
    }

    /** A slider over whole numbers, labelled from its value. */
    static final class Slider extends Widget {
        private final IntFunction<ITextComponent> label;
        private final int min, max;
        private final IntConsumer onChange;
        private double position; // 0 to 1
        private boolean dragging;

        Slider(int x, int y, int width, IntFunction<ITextComponent> label, int min, int max, int value, IntConsumer onChange) {
            super(x, y, width, 20);
            this.label = label;
            this.min = min;
            this.max = max;
            this.onChange = onChange;
            position = (MathHelper.clamp(value, min, max) - min) / (double) (max - min);
            displayString = label.apply(value()).getFormattedText();
        }

        private int value() {
            return min + (int) Math.round(position * (max - min));
        }

        private void moveTo(int mouseX) {
            int before = value();
            position = MathHelper.clamp((mouseX - (x + 4)) / (double) (width - 8), 0, 1);
            int now = value();
            // Snap the knob to the whole number it stands for.
            position = (now - min) / (double) (max - min);
            displayString = label.apply(now).getFormattedText();
            if (now != before) onChange.accept(now);
        }

        @Override
        protected int getHoverState(boolean mouseOver) {
            return 0; // the darker track, like Minecraft's own sliders
        }

        @Override
        public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
            if (!super.mousePressed(mc, mouseX, mouseY)) return false;
            dragging = true;
            moveTo(mouseX);
            return true;
        }

        @Override
        protected void mouseDragged(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) return;
            if (dragging) moveTo(mouseX);
            // The knob, as Minecraft's GuiOptionSlider draws it.
            mc.getTextureManager().bindTexture(BUTTON_TEXTURES);
            GlStateManager.color(1, 1, 1, 1);
            int knob = x + (int) (position * (width - 8));
            int v = enabled ? 66 : 46;
            drawTexturedModalRect(knob, y, 0, v, 4, 20);
            drawTexturedModalRect(knob + 4, y, 196, v, 4, 20);
        }

        @Override
        public void mouseReleased(int mouseX, int mouseY) {
            dragging = false;
        }
    }

    /** A clickable square of color. The one matching the current color gets a white outline. */
    static final class Swatch extends Widget {
        private final int rgb;
        private final IntSupplier current;
        private final IntConsumer onPick;

        Swatch(int x, int y, int size, int rgb, IntSupplier current, IntConsumer onPick) {
            super(x, y, size, size);
            this.rgb = rgb;
            this.current = current;
            this.onPick = onPick;
        }

        @Override
        void onPress() {
            onPick.accept(rgb);
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
            if (!visible) return;
            hovered = mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
            int outline;
            if (!enabled) outline = 0xFF2A2A2A;
            else if (current.getAsInt() == rgb) outline = 0xFFFFFFFF;
            else if (hovered) outline = 0xFFA0A0A0;
            else outline = 0xFF505050;
            Gui.drawRect(x, y, x + width, y + height, outline);
            Gui.drawRect(x + 2, y + 2, x + width - 2, y + height - 2, (enabled ? 0xFF000000 : 0x50000000) | rgb);
        }
    }
}
