package com.desktopscreens.core;

import com.desktopscreens.core.Win32.G32;
import com.desktopscreens.core.Win32.ICONINFO;
import com.desktopscreens.core.Win32.U32;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinNT.HANDLE;

import java.util.HashMap;
import java.util.Map;

/**
 * The mouse cursor as a picture with see-through parts, to draw it into captured pictures of the desktop (capture
 * never includes it). Windows draws its text cursor by inverting what's under it, one pixel wide, and on screens in
 * the world, which show the desktop much smaller than the monitor, it faded into the picture (2026-09-27): pixels the
 * cursor inverts come out black here, with a white edge around them, which reads on any background, also shrunk.
 * Everything else is the cursor Windows shows, whatever it is (custom ones too). Drawn into the picture rather than
 * by the game over it: a crisp one drawn over the picture showed two cursors while the picture still had its own, and
 * without that it still looked like a copy, its pixels growing and shrinking as it moved (2026-10-04).
 */
final class CursorShape {
    /** Bigger than any cursor Windows offers (its largest size setting is 256). */
    private static final int MAX_SIZE = 256;

    final int width, height, hotX, hotY;
    /** Top-down rows of 0xAARRGGBB, not premultiplied. */
    final int[] argb;

    private CursorShape(int width, int height, int hotX, int hotY, int[] argb) {
        this.width = width;
        this.height = height;
        this.hotX = hotX;
        this.hotY = hotY;
        this.argb = argb;
    }

    /**
     * One capture thread's pictures of the cursors it has seen, by handle, each made again once it's a second old:
     * a cursor keeps its handle when its look changes (Windows' pointer style and size settings swap the picture behind
     * the same handle), and a picture kept for good showed the old style after it was set back (2026-10-04). Making one
     * took 0.7 ms for the normal arrow on the test PC; looking one up, almost nothing.
     */
    static final class Cache {
        private static final long REMAKE_NANOS = 1_000_000_000L;

        private final Map<Long, CursorShape> shapes = new HashMap<Long, CursorShape>();
        private final Map<Long, Long> madeAt = new HashMap<Long, Long>();

        /** The picture of cursor {@code handle}; null if Windows won't give it. */
        CursorShape get(long handle) {
            Long key = handle;
            long now = System.nanoTime();
            Long made = madeAt.get(key);
            if (made != null && now - made < REMAKE_NANOS) return shapes.get(key);
            if (made == null && shapes.size() > 256) { // apps can create cursors on the fly; don't grow forever
                shapes.clear();
                madeAt.clear();
            }
            CursorShape shape = of(handle);
            shapes.put(key, shape); // null too, so it isn't tried again every picture
            madeAt.put(key, now);
            return shape;
        }
    }

    /** The picture of cursor {@code handle}, with a pixel of room all round for the edge; null if Windows won't give it. */
    static CursorShape of(long handle) {
        Pointer cursor = new Pointer(handle);
        ICONINFO info = new ICONINFO();
        if (!U32.I.GetIconInfo(cursor, info)) return null;
        int w = 0, h = 0;
        try {
            Win32.BITMAP bm = new Win32.BITMAP();
            if (info.hbmMask != null && G32.I.GetObject(new HANDLE(info.hbmMask), bm.size(), bm) != 0) {
                w = bm.bmWidth;
                // A black-and-white cursor (the text cursor) has its AND and XOR masks stacked in one bitmap.
                h = info.hbmColor == null ? bm.bmHeight / 2 : bm.bmHeight;
            }
        } finally {
            // GetIconInfo gives us copies of the cursor's bitmaps, which we must free.
            if (info.hbmMask != null) G32.I.DeleteObject(new HANDLE(info.hbmMask));
            if (info.hbmColor != null) G32.I.DeleteObject(new HANDLE(info.hbmColor));
        }
        if (w <= 0 || h <= 0 || w > MAX_SIZE || h > MAX_SIZE) return null;
        // Drawn once on black and once on white: what stays black on black and white on white is see-through, the
        // difference between the two gives each pixel's opacity, and what's brighter on black than on white is inverted.
        DesktopCapture.Frame onBlack = new DesktopCapture.Frame(w, h), onWhite = new DesktopCapture.Frame(w, h);
        try {
            long bytes = (long) w * h * 4;
            onBlack.bits.setMemory(0, bytes, (byte) 0);
            onWhite.bits.setMemory(0, bytes, (byte) 0xFF);
            U32.I.DrawIconEx(onBlack.dc(), 0, 0, cursor, w, h, 0, null, Win32.DI_NORMAL);
            U32.I.DrawIconEx(onWhite.dc(), 0, 0, cursor, w, h, 0, null, Win32.DI_NORMAL);
            G32.I.GdiFlush();
            int[] black = onBlack.bits.getIntArray(0, w * h), white = onWhite.bits.getIntArray(0, w * h);
            int ow = w + 2, oh = h + 2;
            int[] out = new int[ow * oh];
            boolean[] inverted = new boolean[ow * oh];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int b = black[y * w + x], wh = white[y * w + x];
                    int br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
                    int wr = wh >> 16 & 255, wg = wh >> 8 & 255, wb = wh & 255;
                    int i = (y + 1) * ow + x + 1;
                    if (br + bg + bb > wr + wg + wb) {
                        inverted[i] = true;
                        continue;
                    }
                    int alpha = 255 - ((wr - br) + (wg - bg) + (wb - bb)) / 3;
                    if (alpha <= 0) continue;
                    alpha = Math.min(255, alpha);
                    out[i] = alpha << 24 | unmultiply(br, alpha) << 16 | unmultiply(bg, alpha) << 8 | unmultiply(bb, alpha);
                }
            }
            for (int i = 0; i < out.length; i++) {
                if (inverted[i]) out[i] = 0xFF000000;
            }
            for (int y = 0; y < oh; y++) {
                for (int x = 0; x < ow; x++) {
                    int i = y * ow + x;
                    if (inverted[i] || out[i] >>> 24 != 0 || !nextToInverted(inverted, ow, oh, x, y)) continue;
                    out[i] = 0xFFFFFFFF;
                }
            }
            return new CursorShape(ow, oh, info.xHotspot + 1, info.yHotspot + 1, out);
        } finally {
            onBlack.free();
            onWhite.free();
        }
    }

    /**
     * Draws it into a picture of {@code pictureWidth} x {@code pictureHeight} pixels at {@code address} (4 bytes each:
     * blue, green, red, and one left as it is; rows top to bottom), its click point at ({@code x}, {@code y}) in the
     * picture.
     */
    void drawInto(long address, int pictureWidth, int pictureHeight, int x, int y) {
        int left = x - hotX, top = y - hotY;
        int x0 = Math.max(0, -left), x1 = Math.min(width, pictureWidth - left);
        int y0 = Math.max(0, -top), y1 = Math.min(height, pictureHeight - top);
        if (x0 >= x1 || y0 >= y1) return;
        Pointer picture = new Pointer(address);
        int[] row = new int[x1 - x0];
        for (int cy = y0; cy < y1; cy++) {
            long at = ((long) (top + cy) * pictureWidth + left + x0) * 4;
            picture.read(at, row, 0, row.length);
            for (int cx = x0; cx < x1; cx++) {
                int c = argb[cy * width + cx], a = c >>> 24;
                if (a == 0) continue;
                int under = row[cx - x0];
                int r = mix(c >> 16 & 255, under >> 16 & 255, a), g = mix(c >> 8 & 255, under >> 8 & 255, a), b = mix(c & 255, under & 255, a);
                row[cx - x0] = under & 0xFF000000 | r << 16 | g << 8 | b;
            }
            picture.write(at, row, 0, row.length);
        }
    }

    private static int mix(int over, int under, int alpha) {
        return (over * alpha + under * (255 - alpha) + 127) / 255;
    }

    private static int unmultiply(int channel, int alpha) {
        return Math.min(255, channel * 255 / alpha);
    }

    private static boolean nextToInverted(boolean[] inverted, int w, int h, int x, int y) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx, ny = y + dy;
                if (nx >= 0 && ny >= 0 && nx < w && ny < h && inverted[ny * w + nx]) return true;
            }
        }
        return false;
    }
}
