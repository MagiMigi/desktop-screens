package com.desktopscreens.core;

/**
 * Where a point on a drawn picture of the desktop is on the real desktop. For the VR panel: Vivecraft's laser arrives
 * as a mouse position in the game window, and the real cursor goes to the same spot of the monitor (or window) shown.
 * One frame's placement; immutable.
 */
public final class PanelMapping {
    private final int picX, picY, picW, picH;
    private final double srcX, srcY, srcW, srcH;
    private final int originX, originY;

    /**
     * @param picX    the picture's rectangle in the game window, in window pixels
     * @param srcX    the part of the frame it shows, in desktop pixels from the monitor's top-left corner
     * @param originX the monitor's top-left corner on the desktop
     */
    public PanelMapping(int picX, int picY, int picW, int picH, double srcX, double srcY, double srcW, double srcH,
                        int originX, int originY) {
        this.picX = picX;
        this.picY = picY;
        this.picW = picW;
        this.picH = picH;
        this.srcX = srcX;
        this.srcY = srcY;
        this.srcW = srcW;
        this.srcH = srcH;
        this.originX = originX;
        this.originY = originY;
    }

    /** The desktop pixel under a point of the game window (window pixels), as {x, y}, or null off the picture. */
    public int[] toDesktop(double windowX, double windowY) {
        return atFraction((windowX - picX) / picW, (windowY - picY) / picH);
    }

    /**
     * The desktop pixel at a fraction of the picture, from its top-left corner (0) to its bottom-right (1), as {x, y},
     * or null off it. For a picture drawn anywhere, like a screen in the world.
     */
    public int[] atFraction(double u, double v) {
        if (u < 0 || u >= 1 || v < 0 || v >= 1) return null;
        return new int[] {originX + along(u, srcX, srcW), originY + along(v, srcY, srcH)};
    }

    /**
     * Where a rectangle of the desktop (a window's frame, say) shows in the game window, as {x, y, w, h} in window
     * pixels, cut to the picture; null when none of it shows.
     */
    public double[] toWindowRect(int x, int y, int w, int h) {
        double scaleX = picW / srcW, scaleY = picH / srcH;
        double left = Math.max(picX, picX + (x - originX - srcX) * scaleX);
        double top = Math.max(picY, picY + (y - originY - srcY) * scaleY);
        double right = Math.min(picX + picW, picX + (x + w - originX - srcX) * scaleX);
        double bottom = Math.min(picY + picH, picY + (y + h - originY - srcY) * scaleY);
        if (right <= left || bottom <= top) return null;
        return new double[] {left, top, right - left, bottom - top};
    }

    /** One axis: a {@code fraction} of the picture, onto the frame's {@code src} part, kept inside it. */
    private static int along(double fraction, double src, double srcSize) {
        int pixel = (int) Math.floor(src + fraction * srcSize);
        int first = (int) Math.ceil(src), last = (int) Math.floor(src + srcSize) - 1;
        return Math.max(first, Math.min(last, pixel));
    }
}
