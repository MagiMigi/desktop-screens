package com.desktopscreens.core;

/**
 * Where the buttons of the VR command bar go, and which one a point is on: side by side, each as wide as it needs,
 * a gap between them, centered in the bar (they reach past its ends if they don't fit). The same for the bar on the
 * panel (GUI units) and the one under a screen in the world (blocks). Only along the bar: its height is the caller's.
 * Immutable.
 */
public final class CommandBar {
    private final double[] xs, ws;

    /**
     * @param itemWidths each item's width, in order
     * @param left       where the bar starts
     * @param width      how long the bar is
     * @param gap        the space between two items
     */
    public CommandBar(double[] itemWidths, double left, double width, double gap) {
        ws = itemWidths.clone();
        xs = new double[ws.length];
        double total = gap * Math.max(0, ws.length - 1);
        for (double w : ws) total += w;
        double x = left + (width - total) / 2;
        for (int i = 0; i < ws.length; i++) {
            xs[i] = x;
            x += ws[i] + gap;
        }
    }

    /** Where item {@code i} starts. */
    public double x(int i) {
        return xs[i];
    }

    /** How wide item {@code i} is. */
    public double w(int i) {
        return ws[i];
    }

    /** The item under {@code x}, or -1 for a gap or outside the items. */
    public int at(double x) {
        for (int i = 0; i < xs.length; i++) {
            if (x >= xs[i] && x < xs[i] + ws[i]) return i;
        }
        return -1;
    }
}
