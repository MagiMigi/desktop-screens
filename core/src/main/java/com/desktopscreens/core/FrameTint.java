package com.desktopscreens.core;

/**
 * The tint of a screen's frame pieces, by the block model's tint index: 1 the top row and its corners, 2 the sides,
 * 3 the bottom row and its corners (plans/2026-11-08-frameless-0.3.5-design.md). With frames on, white: the model's
 * own colors. With frames off, the color that turns each frame color (#2F3036, #26272C, #1C1D21) into the glass's
 * #0B0D11, so the frame disappears into the glass. Index 0 is always white: the game also tints block-breaking and
 * leaf particles with it.
 */
public final class FrameTint {
    public static final int WHITE = 0xFFFFFFFF, TOP = 0xFF3C4550, SIDES = 0xFF4A5563, BOTTOM = 0xFF647283;

    private FrameTint() {}

    /** The tint (ARGB) for a model tint index, with frames on or off. */
    public static int color(boolean frames, int tintIndex) {
        if (frames) return WHITE;
        switch (tintIndex) {
            case 1:
                return TOP;
            case 2:
                return SIDES;
            case 3:
                return BOTTOM;
            default:
                return WHITE;
        }
    }
}
