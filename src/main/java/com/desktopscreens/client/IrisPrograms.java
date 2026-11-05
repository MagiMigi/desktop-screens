package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Tells Iris, when it's installed, which of a shader pack's programs draws our own render pipelines (26.1 on). With
 * a shader pack on, Iris draws every pipeline with one of the pack's programs, and only knows Minecraft's own and the
 * ones it was told about: a screen's picture came out black (Test 68, All the Mods 11 with Complementary, 2026-11-05:
 * "Missing program desktopscreens:pipeline/picture in override list"). Through Iris's own API, looked up by name, so
 * the mod needs no Iris to build or to run. Iris before 26.1 has no such call and is never asked.
 */
final class IrisPrograms {
    private static final String API = "net.irisshaders.iris.api.v0.IrisApi";
    /**
     * The program that draws the desktop picture. Test 69 (Complementary, 2026-11-05): TEXTURED shows it, too bright;
     * EMISSIVE_ENTITIES, BEACON_BEAM and BLOCK drew it black (they're made for vertex formats with more in them).
     */
    static final String PICTURE = "TEXTURED";
    /** A test game cycles the picture through these brightness levels (-PirisProbe) instead of the player's setting. */
    private static final int[] PROBE_LEVELS = {100, 85, 70, 55, 40};
    private static final long PROBE_MILLIS = 3000;
    private static final boolean PROBE = Boolean.getBoolean("desktopscreens.irisProbe");

    /** Iris's API and its isShaderPackInUse, looked up once; null without Iris. */
    private static Object api;
    private static Method inUse;
    private static boolean looked;
    private static int probeShown = -1;

    private IrisPrograms() {}

    /**
     * Has Iris draw {@code pipeline} (a RenderPipeline) with its {@code program} (the name of an IrisProgram).
     * Nothing without Iris; {@code what} names the pipeline in the log.
     */
    static void assign(Object pipeline, String what, String program) {
        Class<?> apiClass;
        try {
            apiClass = Class.forName(API);
        } catch (ClassNotFoundException e) {
            return;
        }
        try {
            // Found by name, its types taken from it: 26.3 moved RenderPipeline (com.mojang.renderpearl.api.pipeline),
            // and asking for the old class failed there (Test 76, 2026-11-05).
            Method assignPipeline = null;
            for (Method m : apiClass.getMethods()) {
                if (m.getName().equals("assignPipeline") && m.getParameterCount() == 2 && m.getParameterTypes()[0].isInstance(pipeline)) {
                    assignPipeline = m;
                }
            }
            if (assignPipeline == null) throw new NoSuchMethodException("IrisApi.assignPipeline for " + pipeline.getClass().getName());
            Class<?> programs = assignPipeline.getParameterTypes()[1];
            Object value = programs.getMethod("valueOf", String.class).invoke(null, program);
            Object iris = apiClass.getMethod("getInstance").invoke(null);
            assignPipeline.invoke(iris, pipeline, value);
            DesktopScreens.LOG.info("Iris: {} is drawn with the shader pack's {} program{}", what, program,
                    PROBE ? " (brightness test: cycling through levels)" : "");
        } catch (InvocationTargetException e) {
            DesktopScreens.LOG.warn("Iris: couldn't have {} drawn with its {} program ({}); with a shader pack on, it may be black",
                    what, program, e.getCause());
        } catch (ReflectiveOperationException | RuntimeException e) {
            DesktopScreens.LOG.warn("Iris: couldn't have {} drawn with its {} program ({}); with a shader pack on, it may be black",
                    what, program, e);
        }
    }

    /** Whether Iris draws with a shader pack right now; false without Iris. */
    static boolean shaderPackInUse() {
        if (!looked) {
            looked = true;
            try {
                Class<?> apiClass = Class.forName(API);
                api = apiClass.getMethod("getInstance").invoke(null);
                inUse = apiClass.getMethod("isShaderPackInUse");
            } catch (ClassNotFoundException e) {
                api = null;
            } catch (ReflectiveOperationException | RuntimeException e) {
                api = null;
                DesktopScreens.LOG.warn("Iris: couldn't ask whether a shader pack is on ({}); screens are drawn as without one", e.toString());
            }
        }
        if (api == null) return false;
        try {
            return (Boolean) inUse.invoke(api);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The vertex color of a picture's corners: white, or grey (dimmer) while a shader pack is on, as bright as the
     * player set ({@link DesktopConfig#shaderBrightness}).
     */
    static int tint() {
        if (!shaderPackInUse()) return 0xFFFFFFFF;
        int percent = PROBE ? probeLevel() : DesktopConfig.shaderBrightness;
        int level = Math.round(255 * percent / 100f);
        return 0xFF000000 | level << 16 | level << 8 | level;
    }

    /** The brightness test's level now, said above the hotbar whenever it changes. */
    private static int probeLevel() {
        int i = (int) (System.currentTimeMillis() / PROBE_MILLIS % PROBE_LEVELS.length);
        if (i != probeShown) {
            probeShown = i;
            Minecraft mc = Minecraft.getInstance();
            if (mc.gui != null) mc.gui.setOverlayMessage(Component.literal("Brightness test: the picture at " + PROBE_LEVELS[i] + " %"), false);
        }
        return PROBE_LEVELS[i];
    }
}
