package com.desktopscreens.client;

import org.lwjgl.system.MemoryUtil;

/**
 * Between the desktop's BGRA pixels and NV12, what H.264 works in: a byte of brightness (Y) per pixel, then a pair of
 * colour bytes (U, V) per 2x2 pixels. BT.601, limited range, both ways, so what goes in comes back out. Works on
 * native memory; either direction can be split into bands of rows run on separate threads.
 */
final class Nv12 {
    private Nv12() {}

    /**
     * Rows {@code y0} to {@code y1} (even) of a {@code width} x {@code height} (both even) BGRA picture at {@code bgra},
     * {@code stride} bytes per row, into the NV12 picture at {@code nv12} (rows of {@code width} bytes).
     */
    static void fromBgra(long bgra, int stride, int width, int height, long nv12, int y0, int y1) {
        long uvPlane = nv12 + (long) width * height;
        for (int y = y0; y < y1; y += 2) {
            long row0 = bgra + (long) y * stride, row1 = row0 + stride;
            long yOut0 = nv12 + (long) y * width, yOut1 = yOut0 + width, uvOut = uvPlane + (long) (y / 2) * width;
            for (int x = 0; x < width; x += 2) {
                int a = MemoryUtil.memGetInt(row0 + x * 4L), b = MemoryUtil.memGetInt(row0 + x * 4L + 4);
                int c = MemoryUtil.memGetInt(row1 + x * 4L), d = MemoryUtil.memGetInt(row1 + x * 4L + 4);
                MemoryUtil.memPutByte(yOut0 + x, luma(a));
                MemoryUtil.memPutByte(yOut0 + x + 1, luma(b));
                MemoryUtil.memPutByte(yOut1 + x, luma(c));
                MemoryUtil.memPutByte(yOut1 + x + 1, luma(d));
                int r = (red(a) + red(b) + red(c) + red(d) + 2) >> 2;
                int g = (green(a) + green(b) + green(c) + green(d) + 2) >> 2;
                int bl = (blue(a) + blue(b) + blue(c) + blue(d) + 2) >> 2;
                MemoryUtil.memPutByte(uvOut + x, (byte) (((-38 * r - 74 * g + 112 * bl + 128) >> 8) + 128));
                MemoryUtil.memPutByte(uvOut + x + 1, (byte) (((112 * r - 94 * g - 18 * bl + 128) >> 8) + 128));
            }
        }
    }

    /**
     * The top-left {@code width} x {@code height} of an NV12 picture at {@code nv12} (rows of {@code stride} bytes, Y for
     * {@code rows} rows, then UV) into tightly packed BGRA at {@code bgra}, fully opaque.
     */
    static void toBgra(long nv12, int stride, int rows, int width, int height, long bgra) {
        long uvPlane = nv12 + (long) stride * rows;
        for (int y = 0; y < height; y++) {
            long yRow = nv12 + (long) y * stride, uvRow = uvPlane + (long) (y / 2) * stride, out = bgra + (long) y * width * 4;
            for (int x = 0; x < width; x++) {
                int c = (MemoryUtil.memGetByte(yRow + x) & 255) - 16;
                int d = (MemoryUtil.memGetByte(uvRow + (x & ~1)) & 255) - 128;
                int e = (MemoryUtil.memGetByte(uvRow + (x & ~1) + 1) & 255) - 128;
                int r = clamp((298 * c + 409 * e + 128) >> 8);
                int g = clamp((298 * c - 100 * d - 208 * e + 128) >> 8);
                int b = clamp((298 * c + 516 * d + 128) >> 8);
                MemoryUtil.memPutInt(out + x * 4L, 0xFF000000 | r << 16 | g << 8 | b);
            }
        }
    }

    private static byte luma(int bgra) {
        return (byte) (((66 * red(bgra) + 129 * green(bgra) + 25 * blue(bgra) + 128) >> 8) + 16);
    }

    private static int red(int bgra) {
        return bgra >> 16 & 255;
    }

    private static int green(int bgra) {
        return bgra >> 8 & 255;
    }

    private static int blue(int bgra) {
        return bgra & 255;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }
}
