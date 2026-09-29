package com.desktopscreens.client;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/** The frame drawn around the desktop picture. */
enum BorderStyle {
    NONE,
    SOLID,
    /** A rainbow that flows around the frame. */
    RAINBOW;

    Component label() {
        return Component.translatable("desktopscreens.border." + name().toLowerCase(Locale.ROOT));
    }
}
