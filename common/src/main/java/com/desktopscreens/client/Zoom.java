package com.desktopscreens.client;

import java.util.Locale;

/**
 * Zoom in the desktop view (Right Ctrl + mouse wheel): magnifies the picture inside its area and follows the
 * mouse pointer. The view only pans when the pointer gets near its edge, so the picture holds still while you
 * work in one spot, and a zoom step keeps the spot under the pointer where it is. Each view starts at 1x.
 */
final class Zoom {
    /**
     * How much each step magnifies, compared with the picture fitted into the area. The same in every scaling
     * mode: pixel-perfect stays exact at the whole-number steps.
     */
    private static final double[] FACTORS = {1, 1.25, 1.5, 2, 2.5, 3, 4, 5, 6};
    /** The pointer is kept at least this share of the visible part away from its edges, where the monitor allows. */
    private static final double EDGE = 0.15;
    /** One notch of a mouse wheel, in Windows' units. Touchpads send less at a time. */
    private static final int NOTCH = 120;

    /** Where the picture goes, in window pixels, and which part of the frame it shows, in desktop pixels. */
    record Placement(int x, int y, int w, int h, double srcX, double srcY, double srcW, double srcH) {}

    private int step;
    private int wheel; // turned less than a notch so far
    /** Top left of the visible part, in desktop pixels. */
    private double srcX, srcY;
    /** Where the pointer was on the screen last frame, relative to the area; NaN if unknown. */
    private double pointerX = Double.NaN, pointerY = Double.NaN;
    private boolean keepPointer;

    boolean active() {
        return step > 0;
    }

    /** Zooms in (positive) or out by whole steps, within 1x to the largest step. */
    void zoomBy(int steps) {
        int next = Math.max(0, Math.min(FACTORS.length - 1, step + steps));
        if (next == step) return;
        step = next;
        keepPointer = true;
    }

    /** Adds wheel movement and returns the whole notches it adds up to. */
    int notches(int delta) {
        wheel += delta;
        int notches = wheel / NOTCH;
        wheel -= notches * NOTCH;
        return notches;
    }

    void reset() {
        step = 0;
        wheel = 0;
        pointerX = pointerY = Double.NaN;
    }

    /** The magnification compared with the fitted picture, like "2x" or "1.25x". */
    String label() {
        String text = String.format(Locale.ROOT, "%.2f", FACTORS[step]).replaceAll("0+$", "").replaceAll("\\.$", "");
        return text + "x";
    }

    /**
     * Places the picture inside an area (window pixels). {@code fit} is the unzoomed scale (screen pixels per
     * desktop pixel), and {@code pointer} the pointer in desktop pixels relative to the monitor, or null if
     * unknown. Call it every frame, zoomed or not, so the first step knows where the pointer was.
     */
    Placement place(int areaX, int areaY, int areaW, int areaH, int frameW, int frameH, double fit, int[] pointer) {
        double s = fit * FACTORS[step];
        // As big as the zoomed picture, up to the whole area: zooming in also uses the room beside a fitted picture.
        int w = (int) Math.min(areaW, Math.round(frameW * s)), h = (int) Math.min(areaH, Math.round(frameH * s));
        int x = areaX + (areaW - w) / 2, y = areaY + (areaH - h) / 2;
        double srcW = Math.min(frameW, w / s), srcH = Math.min(frameH, h / s);
        if (pointer != null) {
            if (keepPointer && !Double.isNaN(pointerX)) {
                srcX = pointer[0] - (pointerX - (x - areaX)) / s;
                srcY = pointer[1] - (pointerY - (y - areaY)) / s;
            } else {
                srcX = follow(srcX, pointer[0], srcW);
                srcY = follow(srcY, pointer[1], srcH);
            }
        }
        keepPointer = false;
        // Whole screen pixels, so the picture doesn't shimmer while it pans, and pixel-perfect stays exact.
        srcX = Math.floor(Math.max(0, Math.min(srcX, frameW - srcW)) * s) / s;
        srcY = Math.floor(Math.max(0, Math.min(srcY, frameH - srcH)) * s) / s;
        if (pointer != null) {
            pointerX = (x - areaX) + (pointer[0] - srcX) * s;
            pointerY = (y - areaY) + (pointer[1] - srcY) * s;
        }
        return new Placement(x, y, w, h, srcX, srcY, srcW, srcH);
    }

    /** Moves the visible part {start, start + visible} just enough to keep the pointer away from its edges. */
    private static double follow(double start, double pointer, double visible) {
        double edge = visible * EDGE;
        if (pointer < start + edge) return pointer - edge;
        if (pointer > start + visible - edge) return pointer - visible + edge;
        return start;
    }
}
