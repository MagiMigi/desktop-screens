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
        if (windowX < picX || windowX >= picX + picW || windowY < picY || windowY >= picY + picH) return null;
        return new int[] {
                originX + along(windowX - picX, picW, srcX, srcW),
                originY + along(windowY - picY, picH, srcY, srcH)};
    }

    /** One axis: {@code offset} into a picture {@code size} long, onto the frame's {@code src} part, kept inside it. */
    private static int along(double offset, int size, double src, double srcSize) {
        int pixel = (int) Math.floor(src + offset * srcSize / size);
        int first = (int) Math.ceil(src), last = (int) Math.floor(src + srcSize) - 1;
        return Math.max(first, Math.min(last, pixel));
    }
}
