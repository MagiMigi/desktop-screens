package com.desktopscreens.core;

/**
 * Where a picture goes on a screen's glass: as big as fits and centered, or stretched to fill the glass when their
 * shapes are within {@link #STRETCH_LIMIT} of each other, so screens of nearly the right shape show no thin black
 * lines (plans/2026-11-08-frameless-0.3.5-design.md, "The small stretch"). Plain arithmetic, no Minecraft.
 */
public final class PictureFit {
    /** How far a picture's shape may be from its area's and still be stretched to fill it: 3.5 %. */
    public static final double STRETCH_LIMIT = 1.035;

    private PictureFit() {}

    /** Whether a picture of {@code picW} x {@code picH} is stretched to fill an area of {@code areaW} x {@code areaH}. */
    public static boolean fills(double areaW, double areaH, int picW, int picH) {
        // areaW / areaH against picW / picH, compared crosswise so the limit itself isn't lost to rounding.
        double a = areaW * picH, p = picW * areaH;
        return Math.max(a, p) <= STRETCH_LIMIT * Math.min(a, p);
    }

    /**
     * The picture's place in the area, {x, y, w, h} with x and y from the area's left and bottom. {@code straighten}
     * 0 fills the area where {@link #fills} allows it, 1 is the picture's true shape (as big as fits, centered), and
     * between is a blend, for the glide into a screen. Too far from the area's shape, it's always the true shape.
     */
    public static float[] place(float areaW, float areaH, int picW, int picH, float straighten) {
        float scale = Math.min(areaW / picW, areaH / picH);
        float w = picW * scale, h = picH * scale;
        float x = (areaW - w) / 2, y = (areaH - h) / 2;
        if (!fills(areaW, areaH, picW, picH)) return new float[] {x, y, w, h};
        float t = Math.max(0, Math.min(1, straighten));
        return new float[] {x * t, y * t, areaW + (w - areaW) * t, areaH + (h - areaH) * t};
    }

    /**
     * The whole number of rows that gives a screen {@code cols} columns wide the shape closest to the picture's,
     * with {@code bezel} (in blocks) kept clear on each side; at least 1.
     */
    public static int nearestRows(int cols, float bezel, int picW, int picH) {
        return Math.max(1, Math.round((cols - 2 * bezel) * picH / picW + 2 * bezel));
    }

    /**
     * How far in front of a picture of {@code width} x {@code height} blocks a camera stands for the picture to just
     * fill its view: {@code fovDegrees} is the vertical field of view actually drawn, {@code aspect} the view's width
     * over its height.
     */
    public static double glideDistance(float width, float height, double fovDegrees, double aspect) {
        double tan = Math.tan(Math.toRadians(fovDegrees) / 2);
        return Math.max(height / 2 / tan, width / 2 / (tan * aspect));
    }

    /**
     * Where a picture floating {@code lift} blocks in front of the glass must end on its low side (left or bottom) to
     * look as if it ends at {@code edge} on the glass, for a viewer at {@code viewer} along the same axis, {@code distance}
     * blocks in front of the glass. Without a frame around it the picture's edge is the screen's edge, and from a steep
     * angle the floating picture would stick out past it. Only ever moves the edge in.
     */
    public static float lowEdge(float edge, float viewer, float distance, float lift) {
        return Math.max(edge, seenAt(edge, viewer, distance, lift));
    }

    /** {@link #lowEdge} for the high side (right or top). */
    public static float highEdge(float edge, float viewer, float distance, float lift) {
        return Math.min(edge, seenAt(edge, viewer, distance, lift));
    }

    private static float seenAt(float edge, float viewer, float distance, float lift) {
        return distance > lift ? edge + (viewer - edge) * lift / distance : edge;
    }
}
