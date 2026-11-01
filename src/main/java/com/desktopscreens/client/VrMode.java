package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector2fc;

import java.lang.reflect.Field;
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
    /** RadialHandler.isShowing() ({@link #radialShowing}); null until found, or when it isn't there. */
    private static Method radialShowing;
    private static boolean radialLooked;
    /** VRClientAPI.instance(), or null without a usable Vivecraft. */
    private static Object api;
    private static Method isVRActive, isSeated;
    /** The main hand's pose (Mod API); null until found, or when it isn't there. */
    private static Method getWorldRenderPose, getMainHand, getPos, getDir;
    private static boolean rayLooked;
    /**
     * The right stick, for scrolling while pointing at a screen in the world: not in the Mod API, so read from
     * Vivecraft's own code by name (MCVR.get().getInputAction(keyRotateAxis).getAxis2DUseTracked(), the same in 1.3.15
     * and 1.3.17). Null until found, or once it failed: no stick scrolling then.
     */
    private static Method mcvrGet, getInputAction, getAxis2D;
    private static KeyMapping rotateAxis;
    private static boolean stickLooked;
    /**
     * Whether Vivecraft's keyboard shows, and when it last noted typing with no menu open: not in the Mod API either,
     * so read from its own code by name (KeyboardHandler.SHOWING and InputSimulator.AIR_TYPING_WARNING_TIME in 1.3.15,
     * Showing and airTypingWarningTime before). Null until found, or when they aren't there: then the cursor follows the
     * laser while the keyboard shows, and the note is only left out of the chat.
     */
    private static Field keyboardShowing, airTypingNoted;
    private static boolean keyboardLooked;
    /** The note's last time before pointing pushed it ahead ({@link #quietTypingNote}), to put back after. */
    private static long airTypingBefore;

    private VrMode() {}

    /**
     * "Use the screen" points at the screen in the world instead of opening the panel: chosen in the settings, and VR
     * with controllers or the test switch. Seated VR keeps the PC behavior.
     */
    static boolean pointing() {
        DesktopConfig.load();
        return DesktopConfig.vrInWorld && (TESTING || vrActive());
    }

    /** The main hand's ray this frame: {x, y, z, dirX, dirY, dirZ} in the world, or null (no VR, or the API failed). */
    static double[] mainHandRay() {
        Object a = api();
        if (a == null || !rayMethods()) return null;
        try {
            Object pose = getWorldRenderPose.invoke(a);
            Object hand = pose == null ? null : getMainHand.invoke(pose);
            if (hand == null) return null;
            Vec3 pos = (Vec3) getPos.invoke(hand), dir = (Vec3) getDir.invoke(hand);
            return pos == null || dir == null ? null : new double[] {pos.x, pos.y, pos.z, dir.x, dir.y, dir.z};
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            DesktopScreens.LOG.info("Vivecraft's hand pose isn't usable, so no pointing at screens: {}", e.toString());
            getWorldRenderPose = null;
            return null;
        }
    }

    private static boolean rayMethods() {
        if (!rayLooked) {
            rayLooked = true;
            try {
                ClassLoader loader = VrMode.class.getClassLoader();
                getWorldRenderPose = Class.forName("org.vivecraft.api.client.VRClientAPI", true, loader).getMethod("getWorldRenderPose");
                getMainHand = Class.forName("org.vivecraft.api.data.VRPose", true, loader).getMethod("getMainHand");
                Class<?> part = Class.forName("org.vivecraft.api.data.VRBodyPartData", true, loader);
                getPos = part.getMethod("getPos");
                getDir = part.getMethod("getDir");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                DesktopScreens.LOG.info("Vivecraft's hand pose isn't there, so no pointing at screens: {}", e.toString());
                getWorldRenderPose = null;
            }
        }
        return getWorldRenderPose != null;
    }

    /** The right stick pushed up (up to 1) or down (down to -1); 0 when it isn't, or can't be read. */
    static float rightStickY() {
        if (!stickLooked) {
            stickLooked = true;
            if (api() != null) findStick();
        }
        if (getAxis2D == null) return 0;
        try {
            Object vr = mcvrGet.invoke(null);
            Object action = vr == null ? null : getInputAction.invoke(vr, rotateAxis);
            return action == null ? 0 : ((Vector2fc) getAxis2D.invoke(action)).y();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            DesktopScreens.LOG.info("Vivecraft's right stick couldn't be read, so no stick scrolling: {}", e.toString());
            getAxis2D = null;
            return 0;
        }
    }

    private static void findStick() {
        try {
            ClassLoader loader = VrMode.class.getClassLoader();
            Class<?> mcvr = Class.forName("org.vivecraft.client_vr.provider.MCVR", true, loader);
            Class<?> mod = Class.forName("org.vivecraft.client.VivecraftVRMod", true, loader);
            mcvrGet = mcvr.getMethod("get");
            getInputAction = mcvr.getMethod("getInputAction", KeyMapping.class);
            rotateAxis = (KeyMapping) mod.getField("keyRotateAxis").get(mod.getField("INSTANCE").get(null));
            getAxis2D = Class.forName("org.vivecraft.client_vr.provider.openvr_lwjgl.VRInputAction", true, loader)
                    .getMethod("getAxis2DUseTracked");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            DesktopScreens.LOG.info("Vivecraft's right stick isn't where it was (a newer Vivecraft?), so no stick scrolling: {}", e.toString());
            getAxis2D = null;
        }
    }

    /** Vivecraft's keyboard is showing; false when it isn't, or that can't be read. */
    static boolean keyboardShowing() {
        findKeyboard();
        if (keyboardShowing == null) return false;
        try {
            return keyboardShowing.getBoolean(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            DesktopScreens.LOG.info("Vivecraft's keyboard couldn't be read: {}", e.toString());
            keyboardShowing = null;
            return false;
        }
    }

    /**
     * While pointing at a screen ({@code quiet}), Vivecraft's note about typing with no menu open never comes: it waits
     * 30 s after the last one, whose time is pushed far ahead meanwhile, and put back after. Only left out of the chat,
     * it still buzzed the controller for players who turned Vivecraft's chat notifications on (2026-10-31).
     */
    static void quietTypingNote(boolean quiet) {
        findKeyboard();
        if (airTypingNoted == null) return;
        try {
            if (quiet) {
                airTypingBefore = airTypingNoted.getLong(null);
                airTypingNoted.setLong(null, Long.MAX_VALUE);
            } else {
                airTypingNoted.setLong(null, airTypingBefore);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            DesktopScreens.LOG.info("Vivecraft's typing note couldn't be held back: {}", e.toString());
            airTypingNoted = null;
        }
    }

    /**
     * Vivecraft's radial menu is showing (RadialHandler.isShowing(), by name, in 1.3.15); false when it isn't, or that
     * can't be read. Its picks are the trigger's too, so the trigger starts no screen meanwhile ({@link TriggerStart}).
     */
    static boolean radialShowing() {
        if (!radialLooked) {
            radialLooked = true;
            if (api() != null) {
                try {
                    radialShowing = Class.forName("org.vivecraft.client_vr.gameplay.screenhandlers.RadialHandler", true,
                            VrMode.class.getClassLoader()).getMethod("isShowing");
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    DesktopScreens.LOG.info("Vivecraft's radial menu isn't where it was (a newer Vivecraft?): {}", e.toString());
                }
            }
        }
        if (radialShowing == null) return false;
        try {
            return (Boolean) radialShowing.invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            DesktopScreens.LOG.info("Vivecraft's radial menu couldn't be read: {}", e.toString());
            radialShowing = null;
            return false;
        }
    }

    private static void findKeyboard() {
        if (keyboardLooked) return;
        keyboardLooked = true;
        if (api() == null) return;
        keyboardShowing = staticField("org.vivecraft.client_vr.gameplay.screenhandlers.KeyboardHandler", "SHOWING", "Showing");
        airTypingNoted = staticField("org.vivecraft.client_vr.provider.InputSimulator", "AIR_TYPING_WARNING_TIME", "airTypingWarningTime");
    }

    /** A static field of Vivecraft's by the first of these names it has, or null (said once). */
    private static Field staticField(String className, String... names) {
        try {
            Class<?> c = Class.forName(className, true, VrMode.class.getClassLoader());
            for (String name : names) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                    // the next name
                }
            }
            DesktopScreens.LOG.info("Vivecraft's {} has none of {} (a newer Vivecraft?)", className, String.join(", ", names));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            DesktopScreens.LOG.info("Vivecraft's {} isn't where it was (a newer Vivecraft?): {}", className, e.toString());
        }
        return null;
    }

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
