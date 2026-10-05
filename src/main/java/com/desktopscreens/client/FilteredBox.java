package com.desktopscreens.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A text box that only ever holds text {@code filter} accepts (a color's hex code, a player name). Minecraft's own
 * boxes did that until 26.1, where vanilla dropped it (NeoForge adds it back, Fabric doesn't): there, typed or pasted
 * text that would break the rule is taken back again, before the box's responder hears of it.
 */
final class FilteredBox extends EditBox {
    //? if >=26.1 {
    /*private final Predicate<String> filter;
    private Consumer<String> responder;
    *///?}

    FilteredBox(Font font, int x, int y, int width, int height, Component name, Predicate<String> filter) {
        super(font, x, y, width, height, name);
        //? if >=26.1 {
        /*this.filter = filter;
        super.setResponder(value -> {
            if (responder != null && filter.test(value)) responder.accept(value);
        });
        *///?} else {
        setFilter(filter);
        //?}
    }

    //? if >=26.1 {
    /*@Override
    public void setResponder(Consumer<String> responder) {
        this.responder = responder;
    }

    @Override
    public void insertText(String text) {
        String before = getValue();
        int cursor = getCursorPosition();
        super.insertText(text);
        if (!filter.test(getValue())) {
            setValue(before);
            moveCursorTo(cursor, false);
        }
    }
    *///?}
}
