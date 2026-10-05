package com.desktopscreens.client;

import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;

import java.util.Locale;

/** The frame drawn around the desktop picture. */
enum BorderStyle {
    NONE,
    SOLID,
    /** A rainbow that flows around the frame. */
    RAINBOW;

    ITextComponent label() {
        return new TextComponentTranslation("desktopscreens.border." + name().toLowerCase(Locale.ROOT));
    }
}
