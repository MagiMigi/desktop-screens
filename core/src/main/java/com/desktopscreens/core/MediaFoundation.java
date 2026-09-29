package com.desktopscreens.core;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * Media Foundation through JNA COM, for the H.264 encoder and decoder that come with Windows ({@link H264Encoder},
 * {@link H264Decoder}). Like {@link Duplication}: every method is a numbered slot in the object's function table,
 * base interfaces first. Checked with a probe on the test PC first (2026-09-24): 1280x720 at 3.9 ms per frame to
 * encode and 3.8 to decode, 17.7 KB per frame at 4 Mbit/s, where JPEG took 50-110 KB.
 */
final class MediaFoundation {
    static final GUID CLSID_H264_ENCODER = new GUID("6ca50344-051a-4ded-9779-a43305165e35");
    static final GUID CLSID_H264_DECODER = new GUID("62ce7e72-4c71-4d20-b15d-452831a87d9d");
    static final GUID IID_IMFTransform = new GUID("bf94c121-5b05-4e6f-8000-ba598961414d");
    static final GUID IID_ICodecAPI = new GUID("901db4c7-31ce-41a2-85dc-8fa0bf41b8da");
    static final GUID MF_MT_MAJOR_TYPE = new GUID("48eba18e-f8c9-4687-bf11-0a74c9f96a8f");
    static final GUID MF_MT_SUBTYPE = new GUID("f7e34c9a-42e8-4714-b74b-cb29d72c35e5");
    static final GUID MFMediaType_Video = new GUID("73646976-0000-0010-8000-00aa00389b71");
    static final GUID MFVideoFormat_H264 = new GUID("34363248-0000-0010-8000-00aa00389b71");
    static final GUID MFVideoFormat_NV12 = new GUID("3231564e-0000-0010-8000-00aa00389b71");
    static final GUID MF_MT_FRAME_SIZE = new GUID("1652c33d-d6b2-4012-b834-72030849a37d");
    static final GUID MF_MT_FRAME_RATE = new GUID("c459a2e8-3d2c-4e44-b132-fee5156c7bb0");
    static final GUID MF_MT_AVG_BITRATE = new GUID("20332624-fb0d-4d9e-bd0d-cbf6786c102e");
    static final GUID MF_MT_INTERLACE_MODE = new GUID("e2724bb8-e676-4806-b4b2-a8d6efb44ccd");
    static final GUID MF_MT_PIXEL_ASPECT_RATIO = new GUID("c6376a1e-8d0a-4027-be45-6d9a0ad39bb6");
    static final GUID MF_MT_MPEG2_PROFILE = new GUID("ad76a80b-2d5c-4e0b-b375-64e520137036");
    static final GUID MF_MT_DEFAULT_STRIDE = new GUID("644b4e48-1e02-4516-b0eb-c01ca9d49ac6");
    /** Also CODECAPI_AVLowLatencyMode: no frames held back. */
    static final GUID MF_LOW_LATENCY = new GUID("9c27891a-ed7a-40e1-88e8-b22727a024ee");
    static final GUID CODECAPI_AVEncMPVGOPSize = new GUID("95f31b26-95a4-41aa-9303-246a7fc6eef1");
    static final GUID CODECAPI_AVEncCommonRateControlMode = new GUID("1c0608e9-370c-4710-8a58-cb6181c42423");
    static final GUID CODECAPI_AVEncVideoForceKeyFrame = new GUID("398c1b98-8353-475a-9ef2-8f265d260345");
    static final GUID MFSampleExtension_CleanPoint = new GUID("9cdf01d8-a0f0-43ba-b077-eaa06cbd728a");

    // IUnknown
    static final int QUERY_INTERFACE = 0, RELEASE = 2;
    // IMFAttributes (and IMFMediaType, IMFSample, which start with it)
    static final int GET_UINT32 = 7, GET_UINT64 = 8, GET_GUID = 10, SET_UINT32 = 21, SET_UINT64 = 22, SET_GUID = 24;
    // IMFSample
    static final int SET_SAMPLE_TIME = 36, SET_SAMPLE_DURATION = 38, GET_BUFFER_BY_INDEX = 40, CONVERT_TO_CONTIGUOUS = 41, ADD_BUFFER = 42;
    // IMFMediaBuffer
    static final int LOCK = 3, UNLOCK = 4, SET_CURRENT_LENGTH = 6;
    // IMFTransform
    static final int GET_OUTPUT_STREAM_INFO = 7, GET_ATTRIBUTES = 8, GET_OUTPUT_AVAILABLE_TYPE = 14, SET_INPUT_TYPE = 15,
            SET_OUTPUT_TYPE = 16, GET_OUTPUT_CURRENT_TYPE = 18, PROCESS_MESSAGE = 23, PROCESS_INPUT = 24, PROCESS_OUTPUT = 25;
    // ICodecAPI
    static final int CODEC_SET_VALUE = 9;

    static final int MFT_MESSAGE_NOTIFY_BEGIN_STREAMING = 0x10000000, MFT_MESSAGE_NOTIFY_START_OF_STREAM = 0x10000003;
    static final int MFT_OUTPUT_STREAM_PROVIDES_SAMPLES = 0x100;
    static final int MF_E_TRANSFORM_NEED_MORE_INPUT = 0xC00D6D72, MF_E_TRANSFORM_STREAM_CHANGE = 0xC00D6D61, MF_E_NO_MORE_TYPES = 0xC00D36B9;
    private static final int RPC_E_CHANGED_MODE = 0x80010106, COINIT_MULTITHREADED = 0, CLSCTX_INPROC_SERVER = 1;
    private static final int MF_VERSION = 0x00020070, MFSTARTUP_FULL = 0;

    /** Held for as long as the game runs: JNA unloads a library nothing references (see {@link Duplication}). */
    private static NativeLibrary mfplat, ole32; // guarded by MediaFoundation.class
    private static boolean started;
    private static final ThreadLocal<Boolean> COM_READY = new ThreadLocal<Boolean>();

    private MediaFoundation() {}

    /** Call on the thread that will use Media Foundation, before anything else: COM for this thread, and Media Foundation once. */
    static void start() {
        synchronized (MediaFoundation.class) {
            if (mfplat == null) mfplat = NativeLibrary.getInstance("mfplat");
            if (ole32 == null) ole32 = NativeLibrary.getInstance("ole32");
        }
        if (COM_READY.get() == null) {
            int hr = ole32.getFunction("CoInitializeEx").invokeInt(new Object[] {null, COINIT_MULTITHREADED});
            // Already initialized (S_FALSE), or single-threaded by someone else: the transforms work either way.
            if (hr < 0 && hr != RPC_E_CHANGED_MODE) check(hr, "CoInitializeEx");
            COM_READY.set(Boolean.TRUE);
        }
        synchronized (MediaFoundation.class) {
            if (!started) {
                check(mfplat.getFunction("MFStartup").invokeInt(new Object[] {MF_VERSION, MFSTARTUP_FULL}), "MFStartup");
                started = true;
            }
        }
    }

    static Pointer createTransform(GUID clsid) {
        PointerByReference p = new PointerByReference();
        check(ole32.getFunction("CoCreateInstance").invokeInt(new Object[] {clsid, null, CLSCTX_INPROC_SERVER, IID_IMFTransform, p}),
                "creating the H.264 transform");
        return p.getValue();
    }

    static Pointer queryInterface(Pointer object, GUID iid) {
        PointerByReference p = new PointerByReference();
        check(call(object, QUERY_INTERFACE, iid, p), "QueryInterface");
        return p.getValue();
    }

    /** A video media type: the subtype, size, frame rate, progressive, square pixels. */
    static Pointer videoType(GUID subtype, int width, int height, int fps) {
        PointerByReference t = new PointerByReference();
        check(mfplat.getFunction("MFCreateMediaType").invokeInt(new Object[] {t}), "MFCreateMediaType");
        Pointer type = t.getValue();
        check(call(type, SET_GUID, MF_MT_MAJOR_TYPE, MFMediaType_Video), "major type");
        check(call(type, SET_GUID, MF_MT_SUBTYPE, subtype), "subtype");
        if (width > 0) check(call(type, SET_UINT64, MF_MT_FRAME_SIZE, (long) width << 32 | height), "frame size");
        if (fps > 0) check(call(type, SET_UINT64, MF_MT_FRAME_RATE, (long) fps << 32 | 1), "frame rate");
        check(call(type, SET_UINT32, MF_MT_INTERLACE_MODE, 2), "progressive"); // MFVideoInterlace_Progressive
        check(call(type, SET_UINT64, MF_MT_PIXEL_ASPECT_RATIO, 1L << 32 | 1), "pixel aspect");
        return type;
    }

    /** A sample with one empty memory buffer of {@code capacity} bytes. */
    static Pointer emptySample(int capacity) {
        PointerByReference s = new PointerByReference(), b = new PointerByReference();
        check(mfplat.getFunction("MFCreateSample").invokeInt(new Object[] {s}), "MFCreateSample");
        int hr = mfplat.getFunction("MFCreateMemoryBuffer").invokeInt(new Object[] {capacity, b});
        if (hr < 0) {
            release(s.getValue());
            check(hr, "MFCreateMemoryBuffer");
        }
        hr = call(s.getValue(), ADD_BUFFER, b.getValue());
        release(b.getValue());
        if (hr < 0) {
            release(s.getValue());
            check(hr, "AddBuffer");
        }
        return s.getValue();
    }

    /** A sample holding {@code data}, with its time and duration in 100 ns units. */
    static Pointer sampleWith(byte[] data, long time, long duration) {
        Pointer sample = emptySample(data.length);
        try {
            Pointer buffer = firstBuffer(sample);
            try {
                PointerByReference bytes = new PointerByReference();
                check(call(buffer, LOCK, bytes, null, null), "Lock");
                bytes.getValue().write(0, data, 0, data.length);
                call(buffer, UNLOCK);
                check(call(buffer, SET_CURRENT_LENGTH, data.length), "SetCurrentLength");
            } finally {
                release(buffer);
            }
            setTimes(sample, time, duration);
            return sample;
        } catch (RuntimeException e) {
            release(sample);
            throw e;
        }
    }

    static void setTimes(Pointer sample, long time, long duration) {
        check(call(sample, SET_SAMPLE_TIME, time), "SetSampleTime");
        check(call(sample, SET_SAMPLE_DURATION, duration), "SetSampleDuration");
    }

    static Pointer firstBuffer(Pointer sample) {
        PointerByReference buffer = new PointerByReference();
        check(call(sample, GET_BUFFER_BY_INDEX, 0, buffer), "GetBufferByIndex");
        return buffer.getValue();
    }

    /** What's in a sample, as one array. */
    static byte[] bytesOf(Pointer sample) {
        PointerByReference b = new PointerByReference();
        check(call(sample, CONVERT_TO_CONTIGUOUS, b), "ConvertToContiguousBuffer");
        Pointer buffer = b.getValue();
        try {
            PointerByReference bytes = new PointerByReference();
            IntByReference length = new IntByReference();
            check(call(buffer, LOCK, bytes, null, length), "Lock");
            try {
                return bytes.getValue().getByteArray(0, length.getValue());
            } finally {
                call(buffer, UNLOCK);
            }
        } finally {
            release(buffer);
        }
    }

    /** ICodecAPI::SetValue with a VT_UI4. Returns the HRESULT: some settings are optional, the caller decides. */
    static int setCodecUInt(Pointer codecApi, GUID api, int value) {
        Memory variant = new Memory(24);
        variant.clear();
        variant.setShort(0, (short) 19); // VT_UI4
        variant.setInt(8, value);
        return call(codecApi, CODEC_SET_VALUE, api, variant);
    }

    /** ICodecAPI::SetValue with VARIANT_TRUE. */
    static int setCodecTrue(Pointer codecApi, GUID api) {
        Memory variant = new Memory(24);
        variant.clear();
        variant.setShort(0, (short) 11); // VT_BOOL
        variant.setShort(8, (short) -1); // VARIANT_TRUE
        return call(codecApi, CODEC_SET_VALUE, api, variant);
    }

    static int call(Pointer object, int slot, Object... args) {
        Pointer table = object.getPointer(0);
        Function f = Function.getFunction(table.getPointer((long) slot * Native.POINTER_SIZE), Function.ALT_CONVENTION);
        Object[] all = new Object[args.length + 1];
        all[0] = object;
        System.arraycopy(args, 0, all, 1, args.length);
        return f.invokeInt(all);
    }

    static void release(Pointer object) {
        if (object != null) call(object, RELEASE);
    }

    static void check(int hr, String what) {
        if (hr < 0) throw new IllegalStateException(what + " failed: " + String.format("0x%08X", hr));
    }
}
