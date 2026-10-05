package com.desktopscreens.client;

import com.sun.jna.Pointer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Between native memory (the captures' pictures, the H.264 coder's buffers) and Java arrays, in one copy each way: on
 * 1.12.2 the sharing code works on arrays, where the newer versions read and write native memory pixel by pixel with
 * LWJGL 3's {@code MemoryUtil}, which Minecraft 1.12.2's LWJGL 2 doesn't have. JNA's bulk copies.
 */
final class Mem {
    private Mem() {}

    static void read(long address, int[] to, int offset, int count) {
        new Pointer(address).read(0, to, offset, count);
    }

    static void read(long address, byte[] to, int offset, int length) {
        new Pointer(address).read(0, to, offset, length);
    }

    static void write(byte[] from, int offset, long address, int length) {
        new Pointer(address).write(0, from, offset, length);
    }

    /** A direct buffer of at least {@code bytes}, in the machine's byte order: {@code buffer} itself if it's big enough. */
    static ByteBuffer direct(ByteBuffer buffer, int bytes) {
        if (buffer != null && buffer.capacity() >= bytes) {
            buffer.clear();
            return buffer;
        }
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }
}
