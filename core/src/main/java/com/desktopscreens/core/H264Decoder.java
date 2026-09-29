package com.desktopscreens.core;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import static com.desktopscreens.core.MediaFoundation.*;

/**
 * Windows' own H.264 decoder (Media Foundation), low latency: each frame comes out as it goes in. It hands out NV12
 * pictures, whose rows may be longer than the picture and whose height may be rounded up to a whole number of
 * 16-row blocks; the caller cuts out the picture's own size. One thread only, the one that opened it.
 */
public final class H264Decoder {
    /** An NV12 picture at {@code address}: rows of {@code stride} bytes, Y for {@code rows} rows, then UV for half as many. */
    public interface Sink {
        void accept(long address, int stride, int rows);
    }

    private Pointer transform;
    private int stride, rows, outputSize;
    private boolean providesSamples;
    private final Memory outputBuffer = new Memory(32); // MFT_OUTPUT_DATA_BUFFER
    private final IntByReference status = new IntByReference();

    private H264Decoder() {
        start();
        transform = createTransform(CLSID_H264_DECODER);
        Pointer inType = null;
        try {
            PointerByReference attributes = new PointerByReference();
            if (call(transform, GET_ATTRIBUTES, attributes) >= 0) {
                call(attributes.getValue(), SET_UINT32, MF_LOW_LATENCY, 1);
                release(attributes.getValue());
            }
            inType = videoType(MFVideoFormat_H264, 0, 0, 0);
            check(call(transform, SET_INPUT_TYPE, 0, inType, 0), "the decoder's input type");
            pickOutputType();
            check(call(transform, PROCESS_MESSAGE, MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0L), "starting the decoder");
        } catch (RuntimeException e) {
            close();
            throw e;
        } finally {
            release(inType);
        }
    }

    /** Opens a decoder, or throws with the reason it can't. */
    public static H264Decoder open() {
        try {
            return new H264Decoder();
        } catch (LinkageError e) { // no mfplat.dll: Windows N without the Media Feature Pack, say
            throw new IllegalStateException("Media Foundation isn't available: " + e.getMessage());
        }
    }

    /** NV12 output, with its size and row length as the decoder says them now. */
    private void pickOutputType() {
        for (int i = 0; ; i++) {
            PointerByReference type = new PointerByReference();
            int hr = call(transform, GET_OUTPUT_AVAILABLE_TYPE, 0, i, type);
            if (hr == MF_E_NO_MORE_TYPES) throw new IllegalStateException("the H.264 decoder offers no NV12 output");
            check(hr, "GetOutputAvailableType");
            try {
                GUID subtype = new GUID();
                if (call(type.getValue(), GET_GUID, MF_MT_SUBTYPE, subtype) < 0 || !subtype.equals(MFVideoFormat_NV12)) continue;
                check(call(transform, SET_OUTPUT_TYPE, 0, type.getValue(), 0), "the decoder's output type");
                LongByReference size = new LongByReference();
                int width = 0;
                if (call(type.getValue(), GET_UINT64, MF_MT_FRAME_SIZE, size) >= 0) {
                    width = (int) (size.getValue() >>> 32);
                    rows = (int) size.getValue();
                }
                IntByReference defaultStride = new IntByReference();
                stride = call(type.getValue(), GET_UINT32, MF_MT_DEFAULT_STRIDE, defaultStride) >= 0 && defaultStride.getValue() > 0
                        ? defaultStride.getValue() : width;
                Memory info = new Memory(12); // MFT_OUTPUT_STREAM_INFO
                check(call(transform, GET_OUTPUT_STREAM_INFO, 0, info), "GetOutputStreamInfo");
                providesSamples = (info.getInt(0) & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES) != 0;
                outputSize = Math.max(info.getInt(4), stride * rows * 3 / 2);
                return;
            } finally {
                release(type.getValue());
            }
        }
    }

    /**
     * Decodes one frame (an Annex B access unit); {@code time} and {@code duration} in 100 ns units. Hands each
     * picture that comes out to {@code sink}, while it's valid.
     */
    public void decode(byte[] frame, long time, long duration, Sink sink) {
        Pointer sample = sampleWith(frame, time, duration);
        try {
            check(call(transform, PROCESS_INPUT, 0, sample, 0), "ProcessInput");
        } finally {
            release(sample);
        }
        while (true) {
            Pointer given = providesSamples || outputSize <= 0 ? null : emptySample(outputSize);
            outputBuffer.clear();
            outputBuffer.setPointer(8, given);
            int hr = call(transform, PROCESS_OUTPUT, 0, 1, outputBuffer, status);
            Pointer got = outputBuffer.getPointer(8);
            Pointer events = outputBuffer.getPointer(24);
            if (events != null) release(events);
            if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT) {
                release(given);
                return;
            }
            if (hr == MF_E_TRANSFORM_STREAM_CHANGE) { // the first frame, or a new size: NV12 again, as it is now
                release(given);
                pickOutputType();
                continue;
            }
            try {
                check(hr, "ProcessOutput");
                deliver(got, sink);
            } finally {
                release(got);
            }
        }
    }

    private void deliver(Pointer sample, Sink sink) {
        PointerByReference b = new PointerByReference();
        check(call(sample, CONVERT_TO_CONTIGUOUS, b), "ConvertToContiguousBuffer");
        Pointer buffer = b.getValue();
        try {
            PointerByReference bytes = new PointerByReference();
            IntByReference length = new IntByReference();
            check(call(buffer, LOCK, bytes, null, length), "Lock");
            try {
                if (stride > 0 && rows > 0 && length.getValue() >= stride * rows * 3 / 2) {
                    sink.accept(Pointer.nativeValue(bytes.getValue()), stride, rows);
                }
            } finally {
                call(buffer, UNLOCK);
            }
        } finally {
            release(buffer);
        }
    }

    public void close() {
        release(transform);
        transform = null;
    }
}
