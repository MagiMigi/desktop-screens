package com.desktopscreens.core;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.ptr.PointerByReference;

import java.nio.ByteBuffer;

/**
 * One Windows Graphics Capture (Windows 10 1903 and newer) of a window or a monitor, used on one thread: pictures
 * straight from the compositor. For a window ({@link WindowCapture}), its own picture, so it comes through while other
 * windows or the game cover it. For a monitor ({@link DesktopCapture}), the desktop under a fullscreen game, where
 * Desktop Duplication keeps being interrupted and GDI took 16-20 ms a picture and cost the game a third of its frames.
 * Windows leaves out windows hidden from capture (the game) either way. Checked with a probe on the test PC first
 * (2026-09-25): a window covered by another came through 218 of 218 times, 0.6 ms to copy 946x533, and Windows 11 let
 * us turn off the yellow frame it otherwise draws around what's captured (Windows 10 keeps it).
 *
 * <p>WinRT through JNA, like {@link Duplication}: every method is a numbered slot in its interface's function table,
 * IInspectable's six first. d3d11, combase and ole32 stay loaded for good (JNA unloads unreferenced libraries).
 */
final class GraphicsCapture {
    /** Where a picture of a given size goes: rows tightly packed, BGRA. */
    interface Target {
        Pointer bufferFor(int width, int height);
    }

    private static final int S_OK = 0, S_FALSE = 1, RPC_E_CHANGED_MODE = 0x80010106, COINIT_MULTITHREADED = 0;
    private static final int D3D_DRIVER_TYPE_HARDWARE = 1, D3D11_CREATE_DEVICE_BGRA_SUPPORT = 0x20, D3D11_SDK_VERSION = 7;
    private static final int B8G8R8A8 = 87, D3D11_USAGE_STAGING = 3, D3D11_CPU_ACCESS_READ = 0x20000, D3D11_MAP_READ = 1;
    private static final int BUFFERS = 2;

    private static final GUID IID_IDXGIDevice = new GUID("54ec77fa-1377-44e6-8c32-88fd5f44c84c");
    private static final GUID IID_IDirect3DDevice = new GUID("a37624ab-8d5f-4650-9d3e-9eae3d9bc670");
    private static final GUID IID_IGraphicsCaptureItemInterop = new GUID("3628e81b-3cac-4c60-b7f4-23ce0e0c3356");
    private static final GUID IID_IGraphicsCaptureItem = new GUID("79c3f95b-31f7-4ec2-a464-632ef5d30760");
    private static final GUID IID_FramePoolStatics2 = new GUID("589b103f-6bbc-5df5-a991-02e28b3b66d5");
    private static final GUID IID_Session2 = new GUID("2c39ae40-7d2e-5044-804e-8b6799d4cf9e");
    private static final GUID IID_Session3 = new GUID("f2cdd966-22ae-5ea1-9596-3a289344c3be");
    private static final GUID IID_DxgiInterfaceAccess = new GUID("a9b3d012-3df2-4ee3-b8d1-8695f457d3c1");
    private static final GUID IID_ID3D11Texture2D = new GUID("6f15aaf2-d208-4e89-9ab4-489535d34f9c");
    private static final GUID IID_IClosable = new GUID("30d5a829-7fa4-4026-83bb-d75bae4ea99e");

    // IUnknown; IInspectable-based interfaces start their own methods at 6
    private static final int QUERY_INTERFACE = 0, RELEASE = 2;
    private static final int INTEROP_CREATE_FOR_WINDOW = 3, INTEROP_CREATE_FOR_MONITOR = 4, ACCESS_GET_INTERFACE = 3;
    private static final int ITEM_GET_SIZE = 7, STATICS_CREATE_FREE_THREADED = 6;
    private static final int POOL_RECREATE = 6, POOL_TRY_GET_NEXT_FRAME = 7, POOL_CREATE_SESSION = 10;
    private static final int SESSION_START = 6, PUT_CURSOR_CAPTURE = 7, PUT_BORDER_REQUIRED = 7, CLOSABLE_CLOSE = 6;
    private static final int FRAME_GET_SURFACE = 6, FRAME_GET_CONTENT_SIZE = 8;
    // ID3D11Device, ID3D11DeviceContext, ID3D11Texture2D
    private static final int CREATE_TEXTURE_2D = 5, MAP = 14, UNMAP = 15, COPY_RESOURCE = 47, TEXTURE_GET_DESC = 10;

    private static NativeLibrary d3d11, combase, ole32; // guarded by GraphicsCapture.class

    private Pointer device, context, dxgiDevice, inspectable, d3dDevice, interop, item, statics, pool, session, staging;
    private int poolWidth, poolHeight, stagingWidth, stagingHeight;
    private final PointerByReference out = new PointerByReference();
    private final Memory size = new Memory(8), desc = new Memory(44), mapped = new Memory(16);
    /** Windows draws no yellow frame around what's captured (Windows 11). */
    final boolean borderless;
    private double averageMillis;

    /** A capture of the window {@code hwnd}. The cursor isn't in it: the caller draws it (it moves without new pictures). */
    static GraphicsCapture ofWindow(long hwnd) {
        return new GraphicsCapture(hwnd, null);
    }

    /** A capture of {@code monitor}, without the cursor. */
    static GraphicsCapture ofMonitor(Monitor monitor) {
        return new GraphicsCapture(0, monitor);
    }

    private GraphicsCapture(long hwnd, Monitor monitor) {
        try {
            initThread();
            PointerByReference deviceOut = new PointerByReference(), contextOut = new PointerByReference();
            check(d3d11.getFunction("D3D11CreateDevice", Function.ALT_CONVENTION).invokeInt(new Object[] {null,
                    D3D_DRIVER_TYPE_HARDWARE, null, D3D11_CREATE_DEVICE_BGRA_SUPPORT, null, 0, D3D11_SDK_VERSION,
                    deviceOut, null, contextOut}), "D3D11CreateDevice");
            device = deviceOut.getValue();
            context = contextOut.getValue();
            dxgiDevice = query(device, IID_IDXGIDevice, "IDXGIDevice");
            check(d3d11.getFunction("CreateDirect3D11DeviceFromDXGIDevice", Function.ALT_CONVENTION).invokeInt(
                    new Object[] {dxgiDevice, out}), "CreateDirect3D11DeviceFromDXGIDevice");
            inspectable = out.getValue();
            d3dDevice = query(inspectable, IID_IDirect3DDevice, "IDirect3DDevice");
            interop = factory("Windows.Graphics.Capture.GraphicsCaptureItem", IID_IGraphicsCaptureItemInterop);
            if (monitor != null) {
                check(call(interop, INTEROP_CREATE_FOR_MONITOR, monitor.handle, IID_IGraphicsCaptureItem, out), "CreateForMonitor");
            } else {
                check(call(interop, INTEROP_CREATE_FOR_WINDOW, Win32.hwnd(hwnd), IID_IGraphicsCaptureItem, out), "CreateForWindow");
            }
            item = out.getValue();
            check(call(item, ITEM_GET_SIZE, size), "GraphicsCaptureItem.Size");
            poolWidth = Math.max(1, size.getInt(0));
            poolHeight = Math.max(1, size.getInt(4));
            statics = factory("Windows.Graphics.Capture.Direct3D11CaptureFramePool", IID_FramePoolStatics2);
            check(call(statics, STATICS_CREATE_FREE_THREADED, d3dDevice, B8G8R8A8, BUFFERS, packed(poolWidth, poolHeight), out),
                    "Direct3D11CaptureFramePool.CreateFreeThreaded");
            pool = out.getValue();
            check(call(pool, POOL_CREATE_SESSION, item, out), "CreateCaptureSession");
            session = out.getValue();
            setFlag(IID_Session2, PUT_CURSOR_CAPTURE, false);
            borderless = setFlag(IID_Session3, PUT_BORDER_REQUIRED, false);
            check(call(session, SESSION_START), "StartCapture");
        } catch (RuntimeException e) {
            close();
            throw e;
        } catch (LinkageError e) {
            close();
            throw new IllegalStateException("Windows Graphics Capture isn't available: " + e.getMessage());
        }
    }

    /** COM on this thread (the multithreaded kind), once. */
    private static void initThread() {
        synchronized (GraphicsCapture.class) {
            if (d3d11 == null) d3d11 = NativeLibrary.getInstance("d3d11");
            if (combase == null) combase = NativeLibrary.getInstance("combase");
            if (ole32 == null) ole32 = NativeLibrary.getInstance("ole32");
        }
        int hr = ole32.getFunction("CoInitializeEx").invokeInt(new Object[] {null, COINIT_MULTITHREADED});
        if (hr != S_OK && hr != S_FALSE && hr != RPC_E_CHANGED_MODE) check(hr, "CoInitializeEx");
    }

    /** Sets a true/false property of the session through a newer interface; false if this Windows lacks it. */
    private boolean setFlag(GUID iid, int slot, boolean value) {
        PointerByReference i = new PointerByReference();
        if (call(session, QUERY_INTERFACE, iid, i) != S_OK) return false;
        try {
            return call(i.getValue(), slot, (byte) (value ? 1 : 0)) == S_OK;
        } finally {
            release(i.getValue());
        }
    }

    /** Time to copy one picture. */
    double averageMillis() {
        return averageMillis;
    }

    /** Copies the next picture into {@code target} if there is one: its size {width, height}, or null if there was none. */
    int[] next(Target target) {
        check(call(pool, POOL_TRY_GET_NEXT_FRAME, out), "TryGetNextFrame");
        Pointer frame = out.getValue();
        if (frame == null) return null;
        Pointer surface = null, access = null, texture = null;
        long start = System.nanoTime();
        try {
            check(call(frame, FRAME_GET_CONTENT_SIZE, size), "ContentSize");
            int w = size.getInt(0), h = size.getInt(4);
            check(call(frame, FRAME_GET_SURFACE, out), "Surface");
            surface = out.getValue();
            access = query(surface, IID_DxgiInterfaceAccess, "IDirect3DDxgiInterfaceAccess");
            check(call(access, ACCESS_GET_INTERFACE, IID_ID3D11Texture2D, out), "ID3D11Texture2D");
            texture = out.getValue();
            call(texture, TEXTURE_GET_DESC, desc);
            int tw = desc.getInt(0), th = desc.getInt(4);
            if (staging == null || tw != stagingWidth || th != stagingHeight) newStaging(tw, th);
            call(context, COPY_RESOURCE, staging, texture);
            int cw = Math.max(0, Math.min(w, tw)), ch = Math.max(0, Math.min(h, th));
            int[] copied = null;
            check(call(context, MAP, staging, 0, D3D11_MAP_READ, 0, mapped), "Map");
            try {
                if (cw > 0 && ch > 0) {
                    Pointer data = mapped.getPointer(0);
                    int pitch = mapped.getInt(Native.POINTER_SIZE), row = cw * 4;
                    ByteBuffer to = target.bufferFor(cw, ch).getByteBuffer(0, (long) row * ch);
                    // The texture can be wider than the picture (padded rows, or a pool not yet the new size): copy
                    // the picture's part row by row. The bulk copies are plain memcpy, as in Duplication.
                    ByteBuffer from = data.getByteBuffer(0, (long) pitch * (ch - 1) + row);
                    if (pitch == row) {
                        to.put(from);
                    } else {
                        for (int y = 0; y < ch; y++) {
                            from.limit(y * pitch + row);
                            from.position(y * pitch);
                            to.put(from);
                        }
                    }
                    copied = new int[] {cw, ch};
                }
            } finally {
                call(context, UNMAP, staging, 0);
            }
            // The window changed size: the frame pool's pictures follow from the next one on.
            if (w > 0 && h > 0 && (w != poolWidth || h != poolHeight)) {
                poolWidth = w;
                poolHeight = h;
                check(call(pool, POOL_RECREATE, d3dDevice, B8G8R8A8, BUFFERS, packed(w, h)), "Recreate");
            }
            if (copied != null) {
                double ms = (System.nanoTime() - start) / 1e6;
                averageMillis = averageMillis == 0 ? ms : averageMillis * 0.95 + ms * 0.05;
            }
            return copied;
        } finally {
            release(texture);
            release(access);
            release(surface);
            closeAndRelease(frame);
        }
    }

    private void newStaging(int w, int h) {
        release(staging);
        staging = null;
        Memory d = new Memory(44);
        d.clear();
        d.setInt(0, w);
        d.setInt(4, h);
        d.setInt(8, 1);   // MipLevels
        d.setInt(12, 1);  // ArraySize
        d.setInt(16, B8G8R8A8);
        d.setInt(20, 1);  // SampleDesc.Count
        d.setInt(28, D3D11_USAGE_STAGING);
        d.setInt(36, D3D11_CPU_ACCESS_READ);
        check(call(device, CREATE_TEXTURE_2D, d, null, out), "CreateTexture2D");
        staging = out.getValue();
        stagingWidth = w;
        stagingHeight = h;
    }

    void close() {
        closeAndRelease(session);
        closeAndRelease(pool);
        release(staging);
        release(statics);
        release(item);
        release(interop);
        release(d3dDevice);
        release(inspectable);
        release(dxgiDevice);
        release(context);
        release(device);
        session = pool = staging = statics = item = interop = d3dDevice = inspectable = dxgiDevice = context = device = null;
    }

    /** WinRT passes a SizeInt32 (two ints) by value as one 64-bit number, width in the low half. */
    private static long packed(int w, int h) {
        return (w & 0xFFFFFFFFL) | ((long) h << 32);
    }

    private static Pointer factory(String cls, GUID iid) {
        PointerByReference string = new PointerByReference(), result = new PointerByReference();
        check(combase.getFunction("WindowsCreateString").invokeInt(new Object[] {new WString(cls), cls.length(), string}), "WindowsCreateString");
        try {
            check(combase.getFunction("RoGetActivationFactory").invokeInt(new Object[] {string.getValue(), iid, result}), "RoGetActivationFactory " + cls);
        } finally {
            combase.getFunction("WindowsDeleteString").invokeInt(new Object[] {string.getValue()});
        }
        return result.getValue();
    }

    private static int call(Pointer object, int slot, Object... args) {
        Pointer fn = object.getPointer(0).getPointer((long) slot * Native.POINTER_SIZE);
        Object[] all = new Object[args.length + 1];
        all[0] = object;
        System.arraycopy(args, 0, all, 1, args.length);
        return Function.getFunction(fn, Function.ALT_CONVENTION).invokeInt(all);
    }

    private static Pointer query(Pointer object, GUID iid, String what) {
        PointerByReference result = new PointerByReference();
        check(call(object, QUERY_INTERFACE, iid, result), what);
        return result.getValue();
    }

    /** WinRT objects that hold something (a frame's buffer, the capture) give it back on Close, not only on the last Release. */
    private static void closeAndRelease(Pointer object) {
        if (object == null) return;
        PointerByReference closable = new PointerByReference();
        if (call(object, QUERY_INTERFACE, IID_IClosable, closable) == S_OK) {
            call(closable.getValue(), CLOSABLE_CLOSE);
            release(closable.getValue());
        }
        release(object);
    }

    private static void release(Pointer object) {
        if (object != null) call(object, RELEASE);
    }

    private static void check(int hr, String what) {
        if (hr != S_OK) throw new IllegalStateException(String.format("%s failed: 0x%08X", what, hr));
    }
}
