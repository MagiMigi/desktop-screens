package com.desktopscreens.core;

import com.sun.jna.Callback;
import com.sun.jna.CallbackReference;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Windows' audio (WASAPI) through JNA COM, for the sound of shared screens: which programs have sound sessions, and
 * recording one program's sound with everything it started ("process loopback", Windows 10 2004 and later). Like
 * {@link MediaFoundation}: every method is a numbered slot in the object's function table, base interfaces first.
 *
 * <p>Checked with probes on the test PC first (2026-09-24): starting a recording takes under 10 ms; each program's
 * sound comes in 10 ms packets of 480 frames, steadily even while it's silent, so several can be mixed by simply
 * adding them up; four programs at once lined up with no gaps.
 */
public final class Wasapi {
    private static final GUID CLSID_MMDeviceEnumerator = new GUID("BCDE0395-E52F-467C-8E3D-C4579291692E");
    private static final GUID IID_IMMDeviceEnumerator = new GUID("A95664D2-9614-4F35-A746-DE8DB63617E6");
    private static final GUID IID_IAudioSessionManager2 = new GUID("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F");
    private static final GUID IID_IAudioSessionControl2 = new GUID("bfb7ff88-7239-4fc9-8fa2-07c950be9c6d");
    private static final GUID IID_IAudioClient = new GUID("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
    private static final GUID IID_IAudioCaptureClient = new GUID("C8ADBD64-E71E-48a0-A4DE-185C395CD317");
    private static final GUID IID_IUnknown = new GUID("00000000-0000-0000-C000-000000000046");
    private static final GUID IID_IAgileObject = new GUID("94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90");
    private static final GUID IID_IActivateAudioInterfaceCompletionHandler = new GUID("41D949AB-9862-444A-80F6-C261334DA5EB");
    private static final String PROCESS_LOOPBACK = "VAD\\Process_Loopback";

    // IUnknown
    private static final int QUERY_INTERFACE = 0, RELEASE = 2;
    // IMMDeviceEnumerator, IMMDeviceCollection, IMMDevice
    private static final int ENUM_AUDIO_ENDPOINTS = 3, COLLECTION_GET_COUNT = 3, COLLECTION_ITEM = 4, DEVICE_ACTIVATE = 3;
    // IAudioSessionManager2, IAudioSessionEnumerator, IAudioSessionControl2
    private static final int GET_SESSION_ENUMERATOR = 5, SESSIONS_GET_COUNT = 3, GET_SESSION = 4;
    private static final int SESSION_GET_STATE = 3, SESSION_GET_PROCESS_ID = 14, SESSION_IS_SYSTEM_SOUNDS = 15;
    // IActivateAudioInterfaceAsyncOperation
    private static final int GET_ACTIVATE_RESULT = 3;
    // IAudioClient, IAudioCaptureClient
    private static final int INITIALIZE = 3, START = 10, STOP = 11, SET_EVENT_HANDLE = 13, GET_SERVICE = 14;
    private static final int GET_BUFFER = 3, RELEASE_BUFFER = 4, GET_NEXT_PACKET_SIZE = 5;

    private static final int CLSCTX_ALL = 0x17, E_RENDER = 0, DEVICE_STATE_ACTIVE = 1, SESSION_EXPIRED = 2;
    private static final int VT_BLOB = 65, ACTIVATION_TYPE_PROCESS_LOOPBACK = 1, INCLUDE_TARGET_PROCESS_TREE = 0;
    private static final int STREAMFLAGS_LOOPBACK = 0x00020000, STREAMFLAGS_EVENTCALLBACK = 0x00040000;
    private static final int STREAMFLAGS_AUTOCONVERTPCM = 0x80000000, STREAMFLAGS_SRC_DEFAULT_QUALITY = 0x08000000;
    private static final int BUFFERFLAGS_SILENT = 2, E_NOINTERFACE = 0x80004002;
    private static final int RPC_E_CHANGED_MODE = 0x80010106, COINIT_MULTITHREADED = 0;
    private static final int WAVE_FORMAT_IEEE_FLOAT = 3;
    /** How much Windows keeps for us between reads, in 100 ns units: 200 ms. */
    private static final long BUFFER_DURATION = 2000000L;

    static final int RATE = 48000, CHANNELS = 2;

    /** Held for as long as the game runs: JNA unloads a library nothing references (see {@link Duplication}). */
    private static NativeLibrary ole32, mmdevapi, kernel32; // guarded by Wasapi.class
    private static final ThreadLocal<Boolean> COM_READY = new ThreadLocal<Boolean>();

    private Wasapi() {}

    /** Call on the thread that will use it, before anything else: COM for this thread. */
    static void start() {
        synchronized (Wasapi.class) {
            if (ole32 == null) ole32 = NativeLibrary.getInstance("ole32");
            if (mmdevapi == null) mmdevapi = NativeLibrary.getInstance("mmdevapi");
            if (kernel32 == null) kernel32 = NativeLibrary.getInstance("kernel32");
        }
        if (COM_READY.get() == null) {
            int hr = ole32.getFunction("CoInitializeEx").invokeInt(new Object[] {null, COINIT_MULTITHREADED});
            if (hr < 0 && hr != RPC_E_CHANGED_MODE) check(hr, "CoInitializeEx");
            COM_READY.set(Boolean.TRUE);
        }
    }

    /** The programs (process ids) with a sound session on any active output, not counting Windows' own system sounds. */
    static Set<Integer> sessionProcesses() {
        Set<Integer> pids = new LinkedHashSet<Integer>();
        PointerByReference enumerator = new PointerByReference();
        check(ole32.getFunction("CoCreateInstance").invokeInt(new Object[] {
                CLSID_MMDeviceEnumerator, null, CLSCTX_ALL, IID_IMMDeviceEnumerator, enumerator}), "the device enumerator");
        PointerByReference devices = new PointerByReference();
        try {
            check(call(enumerator.getValue(), ENUM_AUDIO_ENDPOINTS, E_RENDER, DEVICE_STATE_ACTIVE, devices), "EnumAudioEndpoints");
            IntByReference count = new IntByReference();
            check(call(devices.getValue(), COLLECTION_GET_COUNT, count), "the device count");
            for (int d = 0; d < count.getValue(); d++) {
                PointerByReference device = new PointerByReference();
                if (call(devices.getValue(), COLLECTION_ITEM, d, device) < 0) continue;
                try {
                    addSessions(device.getValue(), pids);
                } finally {
                    release(device.getValue());
                }
            }
        } finally {
            release(devices.getValue());
            release(enumerator.getValue());
        }
        return pids;
    }

    private static void addSessions(Pointer device, Set<Integer> pids) {
        PointerByReference manager = new PointerByReference(), sessions = new PointerByReference();
        if (call(device, DEVICE_ACTIVATE, IID_IAudioSessionManager2, CLSCTX_ALL, null, manager) < 0) return;
        try {
            if (call(manager.getValue(), GET_SESSION_ENUMERATOR, sessions) < 0) return;
            IntByReference count = new IntByReference();
            call(sessions.getValue(), SESSIONS_GET_COUNT, count);
            for (int i = 0; i < count.getValue(); i++) {
                PointerByReference control = new PointerByReference(), control2 = new PointerByReference();
                if (call(sessions.getValue(), GET_SESSION, i, control) < 0) continue;
                if (call(control.getValue(), QUERY_INTERFACE, IID_IAudioSessionControl2, control2) >= 0) {
                    IntByReference state = new IntByReference(), pid = new IntByReference();
                    boolean system = call(control2.getValue(), SESSION_IS_SYSTEM_SOUNDS) == 0; // S_OK: it is
                    if (!system && call(control2.getValue(), SESSION_GET_STATE, state) >= 0 && state.getValue() != SESSION_EXPIRED
                            && call(control2.getValue(), SESSION_GET_PROCESS_ID, pid) >= 0 && pid.getValue() != 0) {
                        pids.add(pid.getValue());
                    }
                    release(control2.getValue());
                }
                release(control.getValue());
            }
        } finally {
            release(sessions.getValue());
            release(manager.getValue());
        }
    }

    /** One program's sound, with everything it started, as 48 kHz stereo float. Only the thread that opened it may use it. */
    static final class Loopback {
        interface Packets {
            /** {@code frames} stereo frames of floats at {@code data}, only valid during the call; null for silence. */
            void accept(Pointer data, int frames);
        }

        final int pid;
        private Pointer client, capture, event;
        private final PointerByReference data = new PointerByReference();
        private final IntByReference frames = new IntByReference(), flags = new IntByReference(), next = new IntByReference();

        Loopback(int pid) {
            this.pid = pid;
            try {
                client = activateProcessLoopback(pid);
                Memory format = new Memory(18); // WAVEFORMATEX
                format.setShort(0, (short) WAVE_FORMAT_IEEE_FLOAT);
                format.setShort(2, (short) CHANNELS);
                format.setInt(4, RATE);
                format.setInt(8, RATE * CHANNELS * 4);
                format.setShort(12, (short) (CHANNELS * 4));
                format.setShort(14, (short) 32);
                format.setShort(16, (short) 0);
                int streamFlags = STREAMFLAGS_LOOPBACK | STREAMFLAGS_EVENTCALLBACK | STREAMFLAGS_AUTOCONVERTPCM | STREAMFLAGS_SRC_DEFAULT_QUALITY;
                check(call(client, INITIALIZE, 0, streamFlags, BUFFER_DURATION, 0L, format, null), "IAudioClient::Initialize");
                event = kernel32.getFunction("CreateEventW").invokePointer(new Object[] {null, 0, 0, null});
                if (event == null) throw new IllegalStateException("CreateEvent failed");
                check(call(client, SET_EVENT_HANDLE, event), "SetEventHandle");
                PointerByReference c = new PointerByReference();
                check(call(client, GET_SERVICE, IID_IAudioCaptureClient, c), "the capture client");
                capture = c.getValue();
                check(call(client, START), "starting the recording");
            } catch (RuntimeException e) {
                close();
                throw e;
            }
        }

        /** Signalled whenever a packet is ready. */
        Pointer event() {
            return event;
        }

        /** Hands every packet that's ready to {@code packets}. */
        void drain(Packets packets) {
            while (true) {
                check(call(capture, GET_NEXT_PACKET_SIZE, next), "GetNextPacketSize");
                if (next.getValue() == 0) return;
                check(call(capture, GET_BUFFER, data, frames, flags, null, null), "GetBuffer");
                try {
                    packets.accept((flags.getValue() & BUFFERFLAGS_SILENT) != 0 ? null : data.getValue(), frames.getValue());
                } finally {
                    call(capture, RELEASE_BUFFER, frames.getValue());
                }
            }
        }

        void close() {
            if (client != null) call(client, STOP);
            release(capture);
            release(client);
            capture = client = null;
            if (event != null) kernel32.getFunction("CloseHandle").invokeInt(new Object[] {event});
            event = null;
        }
    }

    /** Waits for any of {@code handles} to be signalled, at most {@code millis}. */
    static void waitForAny(Memory handles, int count, int millis) {
        kernel32.getFunction("WaitForMultipleObjects").invokeInt(new Object[] {count, handles, 0, millis});
    }

    /**
     * The audio client for one program's sound. Windows hands it over asynchronously, to a completion handler: a COM
     * object we make ourselves out of JNA callbacks.
     */
    private static Pointer activateProcessLoopback(int pid) {
        Memory params = new Memory(12); // AUDIOCLIENT_ACTIVATION_PARAMS
        params.setInt(0, ACTIVATION_TYPE_PROCESS_LOOPBACK);
        params.setInt(4, pid);
        params.setInt(8, INCLUDE_TARGET_PROCESS_TREE);
        Memory variant = new Memory(24); // PROPVARIANT holding a BLOB
        variant.clear();
        variant.setShort(0, (short) VT_BLOB);
        variant.setInt(8, (int) params.size());
        variant.setPointer(16, params);
        CompletionHandler handler = new CompletionHandler();
        PointerByReference operation = new PointerByReference();
        try {
            check(mmdevapi.getFunction("ActivateAudioInterfaceAsync").invokeInt(new Object[] {
                    new WString(PROCESS_LOOPBACK), IID_IAudioClient, variant, handler.object, operation}), "ActivateAudioInterfaceAsync");
            try {
                if (!handler.done.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Windows didn't start the recording in 5 s");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            } finally {
                release(operation.getValue());
            }
        } finally {
            handler.release(); // our own reference; Windows may still hold one, which keeps it alive (LIVE)
        }
        check(handler.result, "recording the program's sound");
        return handler.client;
    }

    // The completion handler's methods. Public, so JNA may call them.
    public interface QueryInterface extends Callback {
        int invoke(Pointer self, Pointer iid, Pointer out);
    }

    public interface RefCount extends Callback {
        int invoke(Pointer self);
    }

    public interface ActivateCompleted extends Callback {
        int invoke(Pointer self, Pointer operation);
    }

    /** Handlers Windows may still call; kept from the garbage collector until their count reaches 0. */
    private static final Set<CompletionHandler> LIVE = Collections.newSetFromMap(new ConcurrentHashMap<CompletionHandler, Boolean>());

    /**
     * IActivateAudioInterfaceCompletionHandler. Windows requires it to be agile (callable from any thread), which a
     * handler with no thread affinity like this one is, so it says yes to IAgileObject.
     */
    private static final class CompletionHandler {
        final Memory object = new Memory(Native.POINTER_SIZE);
        final Memory table = new Memory(4L * Native.POINTER_SIZE);
        final AtomicInteger refs = new AtomicInteger(1);
        final CountDownLatch done = new CountDownLatch(1);
        volatile int result = -1;
        volatile Pointer client;

        final QueryInterface queryInterface = new QueryInterface() {
            @Override
            public int invoke(Pointer self, Pointer iid, Pointer out) {
                GUID asked = new GUID(iid);
                if (asked.equals(IID_IUnknown) || asked.equals(IID_IActivateAudioInterfaceCompletionHandler) || asked.equals(IID_IAgileObject)) {
                    refs.incrementAndGet();
                    out.setPointer(0, self);
                    return 0;
                }
                out.setPointer(0, null);
                return E_NOINTERFACE;
            }
        };
        final RefCount addRef = new RefCount() {
            @Override
            public int invoke(Pointer self) {
                return refs.incrementAndGet();
            }
        };
        final RefCount releaseRef = new RefCount() {
            @Override
            public int invoke(Pointer self) {
                return CompletionHandler.this.release();
            }
        };
        final ActivateCompleted completed = new ActivateCompleted() {
            @Override
            public int invoke(Pointer self, Pointer operation) {
                IntByReference hr = new IntByReference();
                PointerByReference activated = new PointerByReference();
                int got = call(operation, GET_ACTIVATE_RESULT, hr, activated);
                result = got < 0 ? got : hr.getValue();
                client = activated.getValue();
                done.countDown();
                return 0;
            }
        };

        CompletionHandler() {
            table.setPointer(0, CallbackReference.getFunctionPointer(queryInterface));
            table.setPointer(Native.POINTER_SIZE, CallbackReference.getFunctionPointer(addRef));
            table.setPointer(2L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(releaseRef));
            table.setPointer(3L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(completed));
            object.setPointer(0, table);
            LIVE.add(this);
        }

        int release() {
            int left = refs.decrementAndGet();
            if (left == 0) LIVE.remove(this);
            return left;
        }
    }

    private static int call(Pointer object, int slot, Object... args) {
        Pointer table = object.getPointer(0);
        Function f = Function.getFunction(table.getPointer((long) slot * Native.POINTER_SIZE), Function.ALT_CONVENTION);
        Object[] all = new Object[args.length + 1];
        all[0] = object;
        System.arraycopy(args, 0, all, 1, args.length);
        return f.invokeInt(all);
    }

    private static void release(Pointer object) {
        if (object != null) call(object, RELEASE);
    }

    private static void check(int hr, String what) {
        if (hr < 0) throw new IllegalStateException(what + " failed: " + String.format("0x%08X", hr));
    }
}
