package com.desktopscreens.client;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/** How the desktop is sized to fit the game window. */
enum Scaling {
    /** Fill the window, keeping desktop pixels crisp (see desktop.fsh). */
    SHARP,
    /** Fill the window with plain smoothing. */
    SMOOTH,
    /** Whole-number sizes only (1x, 2x...): perfectly sharp, but may leave a border. */
    PIXEL_PERFECT;

    Scaling next() {
        return values()[(ordinal() + 1) % values().length];
    }

    Component label() {
        return Component.translatable("desktopscreens.scaling." + name().toLowerCase(Locale.ROOT));
    }

    /** {x, y, width, height} of the desktop inside an area of the given size, centered. */
    int[] layout(int w, int h, int areaW, int areaH) {
        if (this == PIXEL_PERFECT) {
            int k = Math.min(areaW / w, areaH / h);
            if (k >= 1) return new int[] {(areaW - w * k) / 2, (areaH - h * k) / 2, w * k, h * k};
            // Bigger than the window even at 1x: shrinking can't be pixel-perfect, so just fit it.
        }
        double s = Math.min(areaW / (double) w, areaH / (double) h);
        int fitW = (int) Math.round(w * s), fitH = (int) Math.round(h * s);
        return new int[] {(areaW - fitW) / 2, (areaH - fitH) / 2, fitW, fitH};
    }
}
