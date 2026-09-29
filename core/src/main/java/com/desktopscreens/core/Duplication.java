package com.desktopscreens.core;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.ptr.PointerByReference;

import java.nio.ByteBuffer;

/**
 * DXGI Desktop Duplication of one monitor. Windows hands over each new desktop image as a GPU texture as soon as
 * it's composed (up to the monitor's refresh rate), and nothing while the screen doesn't change. We copy it into a
 * texture the CPU can read, and from there into our own memory. At 1080p that's a few ms, where GDI takes 13-16.
 *
 * <p>Plain COM through JNA: every method is a numbered slot in the object's function table (the slot numbers are
 * the order of the methods in the Windows SDK headers, base interfaces first). Capture thread only: D3D's
 * immediate context isn't thread-safe.
 */
final class Duplication {
    /** What {@link #next} saw. */
    enum Change { NOTHING, POINTER, IMAGE }

    /** The duplication stopped working and must be opened again (resolution change, UAC prompt, lock screen...). */
    static final class Lost extends IllegalStateException {
        Lost() {
            super("DXGI_ERROR_ACCESS_LOST, e.g. a resolution change or a UAC prompt");
        }
    }

    private static final int S_OK = 0;
    private static final int DXGI_ERROR_NOT_FOUND = 0x887A0002, DXGI_ERROR_ACCESS_LOST = 0x887A0026, DXGI_ERROR_WAIT_TIMEOUT = 0x887A0027;
    private static final int D3D_DRIVER_TYPE_UNKNOWN = 0, D3D11_SDK_VERSION = 7;
    private static final int DXGI_FORMAT_B8G8R8A8_UNORM = 87, D3D11_USAGE_STAGING = 3, D3D11_CPU_ACCESS_READ = 0x20000, D3D11_MAP_READ = 1;
    private static final int DXGI_MODE_ROTATION_IDENTITY = 1;

    private static final GUID IID_IDXGIFactory1 = new GUID("770aae78-f26f-4dba-a829-253c83d1b387");
    private static final GUID IID_IDXGIOutput1 = new GUID("00cddea8-939b-4b83-a340-a685226666cc");
    private static final GUID IID_ID3D11Texture2D = new GUID("6f15aaf2-d208-4e89-9ab4-489535d34f9c");

    // IUnknown
    private static final int QUERY_INTERFACE = 0, RELEASE = 2;
    // IDXGIFactory1, IDXGIAdapter, IDXGIOutput, IDXGIOutput1
    private static final int ENUM_ADAPTERS1 = 12, ENUM_OUTPUTS = 7, OUTPUT_GET_DESC = 7, DUPLICATE_OUTPUT = 22;
    // IDXGIOutputDuplication
    private static final int DUPL_GET_DESC = 7, ACQUIRE_NEXT_FRAME = 8, RELEASE_FRAME = 14;
    // ID3D11Device, ID3D11DeviceContext
    private static final int CREATE_TEXTURE_2D = 5, MAP = 14, UNMAP = 15, COPY_RESOURCE = 47;

    /**
     * dxgi.dll and d3d11.dll, held for as long as the game runs. JNA keeps a loaded library only while something
     * references it, then unloads it (FreeLibrary) after a garbage collection. With nothing holding these, that
     * pulled the code out from under live Direct3D objects and their driver threads, and the game died without a
     * crash report a minute or so later.
     */
    private static NativeLibrary dxgi, d3d11; // guarded by Duplication.class

    final int width, height;
    private Pointer device, context, duplication, staging;
    private boolean holdingFrame, haveImage, protectedContent;
    private final Memory frameInfo = new Memory(48); // DXGI_OUTDUPL_FRAME_INFO
    private final Memory mapped = new Memory(16);    // D3D11_MAPPED_SUBRESOURCE
    private final PointerByReference out = new PointerByReference();

    private Duplication(Monitor monitor) {
        width = monitor.width;
        height = monitor.height;
    }

    /**
     * Starts duplicating the monitor, or throws with the reason it can't. A program can only duplicate a monitor
     * once at a time (E_INVALIDARG otherwise), so close the previous one first.
     */
    static Duplication open(Monitor monitor) {
        Duplication d = new Duplication(monitor);
        try {
            d.start(monitor);
            return d;
        } catch (RuntimeException e) {
            d.close();
            throw e;
        } catch (LinkageError e) { // no dxgi.dll or d3d11.dll, or they lack the function
            d.close();
            throw new IllegalStateException("DirectX 11 isn't available: " + e.getMessage());
        }
    }

    private static synchronized void loadLibraries() {
        if (dxgi == null) dxgi = NativeLibrary.getInstance("dxgi");
        if (d3d11 == null) d3d11 = NativeLibrary.getInstance("d3d11");
    }

    private void start(Monitor monitor) {
        loadLibraries();
        Function createFactory = dxgi.getFunction("CreateDXGIFactory1", Function.ALT_CONVENTION);
        Function createDevice = d3d11.getFunction("D3D11CreateDevice", Function.ALT_CONVENTION);
        check(createFactory.invokeInt(new Object[] {IID_IDXGIFactory1, out}), "CreateDXGIFactory1");
        Pointer factory = out.getValue(), adapter = null, output = null, output1 = null;
        try {
            // The graphics card output that is this monitor. With two GPUs (laptops) it's the one wired to that
            // screen, which isn't always the default one, and only that one can duplicate it.
            for (int a = 0; output == null; a++) {
                int hr = call(factory, ENUM_ADAPTERS1, a, out);
                if (hr == DXGI_ERROR_NOT_FOUND) throw new IllegalStateException("no graphics card output is " + monitor.name);
                check(hr, "EnumAdapters1");
                adapter = out.getValue();
                output = findOutput(adapter, monitor.name);
                if (output == null) {
                    release(adapter);
                    adapter = null;
                }
            }
            PointerByReference deviceOut = new PointerByReference(), contextOut = new PointerByReference();
            check(createDevice.invokeInt(new Object[] {adapter, D3D_DRIVER_TYPE_UNKNOWN, null, 0, null, 0, D3D11_SDK_VERSION,
                    deviceOut, null, contextOut}), "D3D11CreateDevice");
            device = deviceOut.getValue();
            context = contextOut.getValue();
            check(call(output, QUERY_INTERFACE, IID_IDXGIOutput1, out), "IDXGIOutput1 (needs Windows 8 or newer)");
            output1 = out.getValue();
            check(call(output1, DUPLICATE_OUTPUT, device, out), "DuplicateOutput");
            duplication = out.getValue();

            Memory desc = new Memory(36); // DXGI_OUTDUPL_DESC
            callVoid(duplication, DUPL_GET_DESC, desc);
            int w = desc.getInt(0), h = desc.getInt(4), rotation = desc.getInt(28);
            if (w != width || h != height)
                throw new IllegalStateException("the monitor is " + width + "x" + height + " but Desktop Duplication gives " + w + "x" + h);
            if (rotation > DXGI_MODE_ROTATION_IDENTITY) throw new IllegalStateException("rotated monitors aren't supported yet");
            staging = createStagingTexture();
        } finally {
            release(output1);
            release(output);
            release(adapter);
            release(factory);
        }
    }

    private Pointer findOutput(Pointer adapter, String deviceName) {
        Memory desc = new Memory(96); // DXGI_OUTPUT_DESC, which starts with the device name
        for (int i = 0; ; i++) {
            int hr = call(adapter, ENUM_OUTPUTS, i, out);
            if (hr == DXGI_ERROR_NOT_FOUND) return null;
            check(hr, "EnumOutputs");
            Pointer output = out.getValue();
            if (call(output, OUTPUT_GET_DESC, desc) == S_OK && deviceName.equals(desc.getWideString(0))) return output;
            release(output);
        }
    }

    /** A texture in memory the CPU can read, the same size and format as the desktop image. */
    private Pointer createStagingTexture() {
        Memory desc = new Memory(44); // D3D11_TEXTURE2D_DESC
        desc.clear();
        desc.setInt(0, width);
        desc.setInt(4, height);
        desc.setInt(8, 1);  // MipLevels
        desc.setInt(12, 1); // ArraySize
        desc.setInt(16, DXGI_FORMAT_B8G8R8A8_UNORM);
        desc.setInt(20, 1); // SampleDesc.Count
        desc.setInt(28, D3D11_USAGE_STAGING);
        desc.setInt(36, D3D11_CPU_ACCESS_READ);
        check(call(device, CREATE_TEXTURE_2D, desc, null, out), "CreateTexture2D");
        return out.getValue();
    }

    /**
     * Waits up to {@code timeoutMillis} for the screen to change. After {@link Change#IMAGE}, {@link #copyTo} has the
     * new picture. {@link Change#POINTER} means only the mouse moved (or changed shape).
     */
    Change next(int timeoutMillis) {
        if (holdingFrame) {
            // Released just before the next one, as Microsoft recommends; our copy of it is done by now.
            holdingFrame = false;
            // If this fails the frame stays held, and the next AcquireNextFrame would only say DXGI_ERROR_INVALID_CALL.
            int released = call(duplication, RELEASE_FRAME);
            if (released == DXGI_ERROR_ACCESS_LOST) throw new Lost();
            check(released, "ReleaseFrame");
        }
        int hr = call(duplication, ACQUIRE_NEXT_FRAME, timeoutMillis, frameInfo, out);
        if (hr == DXGI_ERROR_WAIT_TIMEOUT) return Change.NOTHING;
        if (hr == DXGI_ERROR_ACCESS_LOST) throw new Lost();
        check(hr, "AcquireNextFrame");
        holdingFrame = true;
        Pointer resource = out.getValue();
        try {
            // The first frame is always mouse-only, with an empty image; the whole desktop follows right after
            // (5-20 ms on the test PC, even with nothing moving). Mouse moves before that have nothing to go on.
            long lastPresent = frameInfo.getLong(0), lastMouseUpdate = frameInfo.getLong(8);
            if (lastPresent != 0) {
                check(call(resource, QUERY_INTERFACE, IID_ID3D11Texture2D, out), "ID3D11Texture2D");
                Pointer texture = out.getValue();
                callVoid(context, COPY_RESOURCE, staging, texture);
                release(texture);
                haveImage = true;
                protectedContent = frameInfo.getInt(24) != 0; // ProtectedContentMaskedOut
                return Change.IMAGE;
            }
            return lastMouseUpdate != 0 && haveImage ? Change.POINTER : Change.NOTHING;
        } finally {
            release(resource);
        }
    }

    /**
     * Whether Windows blacked out protected video (DRM, like streaming services) in the newest picture. Every screen
     * capture gets it black; only the screen itself shows it.
     */
    boolean protectedContentHidden() {
        return protectedContent;
    }

    /** Copies the newest picture into {@code destination}: {@code width * height} top-down BGRA pixels, rows packed tightly. */
    void copyTo(Pointer destination) {
        check(call(context, MAP, staging, 0, D3D11_MAP_READ, 0, mapped), "Map");
        try {
            Pointer data = mapped.getPointer(0);
            int pitch = mapped.getInt(8), row = width * 4;
            ByteBuffer to = destination.getByteBuffer(0, (long) row * height);
            if (pitch == row) {
                to.put(data.getByteBuffer(0, (long) row * height));
            } else {
                // Rows of the texture can be padded; the bulk copies below are plain memcpy.
                ByteBuffer from = data.getByteBuffer(0, (long) pitch * (height - 1) + row);
                for (int y = 0; y < height; y++) {
                    from.limit(y * pitch + row);
                    from.position(y * pitch);
                    to.put(from);
                }
            }
        } finally {
            callVoid(context, UNMAP, staging, 0);
        }
    }

    void close() {
        if (holdingFrame) call(duplication, RELEASE_FRAME);
        holdingFrame = false;
        release(staging);
        release(duplication);
        release(context);
        release(device);
        staging = duplication = context = device = null;
    }

    private static int call(Pointer object, int slot, Object... args) {
        return method(object, slot).invokeInt(withThis(object, args));
    }

    private static void callVoid(Pointer object, int slot, Object... args) {
        method(object, slot).invokeVoid(withThis(object, args));
    }

    private static Function method(Pointer object, int slot) {
        Pointer table = object.getPointer(0);
        return Function.getFunction(table.getPointer((long) slot * Native.POINTER_SIZE), Function.ALT_CONVENTION);
    }

    private static Object[] withThis(Pointer object, Object[] args) {
        Object[] all = new Object[args.length + 1];
        all[0] = object;
        System.arraycopy(args, 0, all, 1, args.length);
        return all;
    }

    private static void release(Pointer object) {
        if (object != null) call(object, RELEASE);
    }

    private static void check(int hr, String what) {
        if (hr < 0) throw new IllegalStateException(what + " failed: " + describe(hr));
    }

    /** The error's name where it's one of the usual ones, so the log says what went wrong. */
    static String describe(int hr) {
        String hex = String.format("0x%08X", hr);
        switch (hr) {
            case 0x887A0001: return "DXGI_ERROR_INVALID_CALL (" + hex + ")";
            case 0x887A0004: return "DXGI_ERROR_UNSUPPORTED (" + hex + ")";
            case 0x887A0022: return "DXGI_ERROR_NOT_CURRENTLY_AVAILABLE, too many apps capturing (" + hex + ")";
            case 0x887A0026: return "DXGI_ERROR_ACCESS_LOST (" + hex + ")";
            case 0x887A0028: return "DXGI_ERROR_SESSION_DISCONNECTED (" + hex + ")";
            case 0x887A0005: return "DXGI_ERROR_DEVICE_REMOVED (" + hex + ")";
            case 0x80070005: return "E_ACCESSDENIED (" + hex + ")";
            case 0x80004002: return "E_NOINTERFACE (" + hex + ")";
            case 0x80070057: return "E_INVALIDARG, e.g. this program already duplicates that monitor (" + hex + ")";
            default: return hex;
        }
    }
}
