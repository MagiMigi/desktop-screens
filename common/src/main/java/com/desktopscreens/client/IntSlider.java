package com.desktopscreens.client;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/** A slider over whole numbers; its label is a translation with the value as %s, or any label made from the value. */
final class IntSlider extends AbstractSliderButton {
    private final IntFunction<Component> label;
    private final int min, max;
    private final IntConsumer onChange;

    IntSlider(int x, int y, int width, int height, String labelKey, int min, int max, int value, IntConsumer onChange) {
        this(x, y, width, height, v -> Component.translatable(labelKey, v), min, max, value, onChange);
    }

    IntSlider(int x, int y, int width, int height, IntFunction<Component> label, int min, int max, int value, IntConsumer onChange) {
        super(x, y, width, height, Component.empty(), (value - min) / (double) (max - min));
        this.label = label;
        this.min = min;
        this.max = max;
        this.onChange = onChange;
        updateMessage();
    }

    private int intValue() {
        return min + (int) Math.round(value * (max - min));
    }

    @Override
    protected void updateMessage() {
        setMessage(label.apply(intValue()));
    }

    @Override
    protected void applyValue() {
        onChange.accept(intValue());
    }
}
