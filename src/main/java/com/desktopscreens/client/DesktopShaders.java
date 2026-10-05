package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
//? if >=26.1 {
/*import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.RenderPipelines;
*///?} else {
import net.minecraft.client.renderer.ShaderInstance;
//?}
import net.minecraft.resources.ResourceLocation;

/** Our core shaders. Before 26.1 each loader registers them its own way and hands the result back here. */
public final class DesktopShaders {
    //? if >=26.1 {
    /*/^*
     * Sharp scaling in the GUI (assets/desktopscreens/shaders/core/desktop_sharp.*): Minecraft's GUI picture pipeline
     * with our shaders. Compiled the first time it's used, like any pipeline Minecraft doesn't list itself.
     ^/
    private static final RenderPipeline SHARP = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withLocation(DesktopScreens.id("pipeline/desktop_sharp"))
            .withVertexShader(DesktopScreens.id("core/desktop_sharp"))
            .withFragmentShader(DesktopScreens.id("core/desktop_sharp"))
            .build();
    private static Boolean sharpWorks;

    /^* The sharp pipeline, or null if its shaders don't compile (then plain scaling is used, and that's said once). ^/
    static RenderPipeline sharp() {
        if (sharpWorks == null) {
            sharpWorks = RenderSystem.getDevice().precompilePipeline(SHARP).isValid();
            if (!sharpWorks) DesktopScreens.LOG.error("Couldn't compile the desktop shader; falling back to plain scaling");
        }
        return sharpWorks ? SHARP : null;
    }
    *///?} else {
    /** assets/desktopscreens/shaders/core/desktop.json; uses the POSITION_TEX vertex format. */
    public static final ResourceLocation DESKTOP_ID = DesktopScreens.id("desktop");

    /** Null until resources have loaded (or if the shader failed), in which case plain scaling is used. */
    public static ShaderInstance desktop;
    //?}

    private DesktopShaders() {}
}
