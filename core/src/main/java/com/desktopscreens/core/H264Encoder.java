package com.desktopscreens.core;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.io.ByteArrayOutputStream;

import static com.desktopscreens.core.MediaFoundation.*;

/**
 * Windows' own H.264 encoder (Media Foundation, the software one: the same on every PC, 3.9 ms per 1280x720 frame on
 * the test PC). Baseline profile, so no frame waits for a later one; low latency, so each frame comes out as it goes
 * in; constant bitrate; a keyframe every 2 seconds, and on request. Its output is Annex B (start codes), with the
 * sequence headers in every keyframe. One thread only, the one that opened it.
 */
public final class H264Encoder {
    /** One encoded frame. */
    public static final class Frame {
        public final byte[] data;
        public final boolean keyframe;

        Frame(byte[] data, boolean keyframe) {
            this.data = data;
            this.keyframe = keyframe;
        }
    }

    /** Writes an NV12 picture at {@code address}: {@code width * height} bytes of Y, then the same half as many of UV. */
    public interface Filler {
        void fill(long address);
    }

    public final int width, height;
    private Pointer transform, codecApi;
    private final int outputSize;
    private final boolean providesSamples;
    private final Memory outputBuffer = new Memory(32); // MFT_OUTPUT_DATA_BUFFER
    private final IntByReference status = new IntByReference();

    private H264Encoder(int width, int height, int fps, int bitrate) {
        this.width = width;
        this.height = height;
        start();
        transform = createTransform(CLSID_H264_ENCODER);
        Pointer outType = null, inType = null;
        try {
            codecApi = queryInterface(transform, IID_ICodecAPI);
            // Before the types: some of these only count then. None is essential, so only the rate control is checked.
            check(setCodecUInt(codecApi, CODECAPI_AVEncCommonRateControlMode, 0), "constant bitrate"); // eAVEncCommonRateControlMode_CBR
            setCodecUInt(codecApi, CODECAPI_AVEncMPVGOPSize, fps * 2);
            setCodecTrue(codecApi, MF_LOW_LATENCY);
            outType = videoType(MFVideoFormat_H264, width, height, fps);
            check(call(outType, SET_UINT32, MF_MT_AVG_BITRATE, bitrate), "bitrate");
            check(call(outType, SET_UINT32, MF_MT_MPEG2_PROFILE, 66), "baseline profile"); // eAVEncH264VProfile_Base
            check(call(transform, SET_OUTPUT_TYPE, 0, outType, 0), "the encoder's output type");
            inType = videoType(MFVideoFormat_NV12, width, height, fps);
            check(call(transform, SET_INPUT_TYPE, 0, inType, 0), "the encoder's input type");
            check(call(transform, PROCESS_MESSAGE, MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0L), "starting the encoder");
            check(call(transform, PROCESS_MESSAGE, MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0L), "starting the stream");
            Memory info = new Memory(12); // MFT_OUTPUT_STREAM_INFO
            check(call(transform, GET_OUTPUT_STREAM_INFO, 0, info), "GetOutputStreamInfo");
            providesSamples = (info.getInt(0) & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES) != 0;
            outputSize = Math.max(info.getInt(4), width * height);
        } catch (RuntimeException e) {
            close();
            throw e;
        } finally {
            release(outType);
            release(inType);
        }
    }

    /** Opens an encoder for pictures this size (both even), or throws with the reason it can't. */
    public static H264Encoder open(int width, int height, int fps, int bitrate) {
        try {
            return new H264Encoder(width, height, fps, bitrate);
        } catch (LinkageError e) { // no mfplat.dll: Windows N without the Media Feature Pack, say
            throw new IllegalStateException("Media Foundation isn't available: " + e.getMessage());
        }
    }

    /**
     * Encodes one picture, which {@code fill} writes. {@code time} and {@code duration} are in 100 ns units. Returns
     * the frame, or null if the encoder held it back (it shouldn't, in low latency mode).
     */
    public Frame encode(Filler fill, long time, long duration, boolean keyframe) {
        if (keyframe) setCodecUInt(codecApi, CODECAPI_AVEncVideoForceKeyFrame, 1);
        int size = width * height * 3 / 2;
        Pointer sample = emptySample(size);
        try {
            Pointer buffer = firstBuffer(sample);
            try {
                PointerByReference bytes = new PointerByReference();
                check(call(buffer, LOCK, bytes, null, null), "Lock");
                try {
                    fill.fill(Pointer.nativeValue(bytes.getValue()));
                } finally {
                    call(buffer, UNLOCK);
                }
                check(call(buffer, SET_CURRENT_LENGTH, size), "SetCurrentLength");
            } finally {
                release(buffer);
            }
            setTimes(sample, time, duration);
            check(call(transform, PROCESS_INPUT, 0, sample, 0), "ProcessInput");
        } finally {
            release(sample);
        }
        ByteArrayOutputStream out = null;
        boolean key = false;
        while (true) {
            Pointer given = providesSamples ? null : emptySample(outputSize);
            outputBuffer.clear();
            outputBuffer.setPointer(8, given);
            int hr = call(transform, PROCESS_OUTPUT, 0, 1, outputBuffer, status);
            Pointer got = outputBuffer.getPointer(8);
            Pointer events = outputBuffer.getPointer(24);
            if (events != null) release(events);
            if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT) {
                release(given);
                break;
            }
            try {
                check(hr, "ProcessOutput");
                if (out == null) out = new ByteArrayOutputStream(64 * 1024);
                byte[] data = bytesOf(got);
                out.write(data, 0, data.length);
                IntByReference clean = new IntByReference();
                if (call(got, GET_UINT32, MFSampleExtension_CleanPoint, clean) >= 0 && clean.getValue() != 0) key = true;
            } finally {
                release(got);
            }
        }
        return out == null ? null : new Frame(out.toByteArray(), key);
    }

    public void close() {
        release(codecApi);
        release(transform);
        codecApi = transform = null;
    }
}
