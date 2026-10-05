package com.desktopscreens.client;

/**
 * Between the desktop's BGRA pixels and NV12, what H.264 works in: a byte of brightness (Y) per pixel, then a pair of
 * colour bytes (U, V) per 2x2 pixels. BT.601, limited range, both ways, so what goes in comes back out. On 1.12.2 it
 * works on arrays (see {@link Mem}): a BGRA pixel is one int, 0xAARRGGBB, as the bytes B, G, R, A read in the PC's
 * byte order. Either direction can be split into bands of rows run on separate threads.
 */
final class Nv12 {
    private Nv12() {}

    /**
     * Rows {@code y0} to {@code y1} (even) of a {@code width} x {@code height} (both even) picture in {@code bgra},
     * {@code stride} pixels per row, into the NV12 picture {@code nv12} (rows of {@code width} bytes).
     */
    static void fromBgra(int[] bgra, int stride, int width, int height, byte[] nv12, int y0, int y1) {
        int uvPlane = width * height;
        for (int y = y0; y < y1; y += 2) {
            int row0 = y * stride, row1 = row0 + stride;
            int yOut0 = y * width, yOut1 = yOut0 + width, uvOut = uvPlane + (y / 2) * width;
            for (int x = 0; x < width; x += 2) {
                int a = bgra[row0 + x], b = bgra[row0 + x + 1];
                int c = bgra[row1 + x], d = bgra[row1 + x + 1];
                nv12[yOut0 + x] = luma(a);
                nv12[yOut0 + x + 1] = luma(b);
                nv12[yOut1 + x] = luma(c);
                nv12[yOut1 + x + 1] = luma(d);
                int r = (red(a) + red(b) + red(c) + red(d) + 2) >> 2;
                int g = (green(a) + green(b) + green(c) + green(d) + 2) >> 2;
                int bl = (blue(a) + blue(b) + blue(c) + blue(d) + 2) >> 2;
                nv12[uvOut + x] = (byte) (((-38 * r - 74 * g + 112 * bl + 128) >> 8) + 128);
                nv12[uvOut + x + 1] = (byte) (((112 * r - 94 * g - 18 * bl + 128) >> 8) + 128);
            }
        }
    }

    /**
     * The top-left {@code width} x {@code height} of an NV12 picture in {@code nv12} (rows of {@code stride} bytes, Y for
     * {@code rows} rows, then UV) into tightly packed BGRA in {@code bgra}, fully opaque.
     */
    static void toBgra(byte[] nv12, int stride, int rows, int width, int height, int[] bgra) {
        int uvPlane = stride * rows;
        for (int y = 0; y < height; y++) {
            int yRow = y * stride, uvRow = uvPlane + (y / 2) * stride, out = y * width;
            for (int x = 0; x < width; x++) {
                int c = (nv12[yRow + x] & 255) - 16;
                int d = (nv12[uvRow + (x & ~1)] & 255) - 128;
                int e = (nv12[uvRow + (x & ~1) + 1] & 255) - 128;
                int r = clamp((298 * c + 409 * e + 128) >> 8);
                int g = clamp((298 * c - 100 * d - 208 * e + 128) >> 8);
                int b = clamp((298 * c + 516 * d + 128) >> 8);
                bgra[out + x] = 0xFF000000 | r << 16 | g << 8 | b;
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
