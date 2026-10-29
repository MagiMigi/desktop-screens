package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;

import java.lang.reflect.Method;

/**
 * Whether "Use the screen" shows the desktop on Vivecraft's floating panel (the VR panel view): Vivecraft running in
 * VR with controllers, or the test switch. Vivecraft's Mod API (since its 1.3.0) is asked by name, so nothing here
 * needs Vivecraft to build or to load; no Vivecraft, an older one, or an API that fails all mean "no VR".
 * Seated VR (a headset with mouse and keyboard, no controllers) keeps the PC behavior, except on Minecraft's own
 * monitor (see {@link DesktopScreen}). Game thread.
 */
final class VrMode {
    /** The panel view without VR, to test it with a mouse: -Ddesktopscreens.vrPanelTest=true (gradlew ... -PvrTest). */
    private static final boolean TESTING = Boolean.getBoolean("desktopscreens.vrPanelTest");

    private static boolean looked;
    /** VRClientAPI.instance(), or null without a usable Vivecraft. */
    private static Object api;
    private static Method isVRActive, isSeated;

    private VrMode() {}

    /** The test switch is on. */
    static boolean testing() {
        return TESTING;
    }

    /** "Use the screen" shows the desktop on the panel, and its mouse and key events go to the PC. */
    static boolean panel() {
        return TESTING || vrActive();
    }

    /** Vivecraft is in VR, with controllers. */
    static boolean vrActive() {
        return inVr(false);
    }

    /** Vivecraft is in seated VR: a headset, with the mouse and keyboard instead of controllers. */
    static boolean seatedVr() {
        return inVr(true);
    }

    private static boolean inVr(boolean seated) {
        Object a = api();
        if (a == null) return false;
        try {
            return (Boolean) isVRActive.invoke(a) && (Boolean) isSeated.invoke(a) == seated;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            unusable(e);
            return false;
        }
    }

    /** For the log: what VR this game is in. */
    static String describe() {
        String vivecraft;
        Object a = api();
        if (a == null) {
            vivecraft = "Vivecraft not installed (or older than its Mod API)";
        } else {
            try {
                vivecraft = !(Boolean) isVRActive.invoke(a) ? "Vivecraft: VR off"
                        : (Boolean) isSeated.invoke(a) ? "Vivecraft: seated VR" : "Vivecraft: VR with controllers";
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                unusable(e);
                vivecraft = "Vivecraft: its Mod API failed";
            }
        }
        return TESTING ? "VR panel test switch (" + vivecraft + ")" : vivecraft;
    }

    private static Object api() {
        if (!looked) {
            looked = true;
            try {
                Class<?> c = Class.forName("org.vivecraft.api.client.VRClientAPI", true, VrMode.class.getClassLoader());
                isVRActive = c.getMethod("isVRActive");
                isSeated = c.getMethod("isSeated");
                api = c.getMethod("instance").invoke(null);
            } catch (ClassNotFoundException e) {
                // Not installed, or a Vivecraft from before its Mod API: no VR.
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                unusable(e);
            }
        }
        return api;
    }

    /** Said once; from then on there's no VR. */
    private static void unusable(Throwable e) {
        DesktopScreens.LOG.info("Vivecraft found, but its Mod API isn't usable, so no VR panel: {}", e.toString());
        api = null;
    }
}
