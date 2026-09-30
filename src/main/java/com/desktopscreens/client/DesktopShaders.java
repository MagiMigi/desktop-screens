package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

/** Our core shaders. Each loader registers them its own way and hands the result back here. */
public final class DesktopShaders {
    /** assets/desktopscreens/shaders/core/desktop.json; uses the POSITION_TEX vertex format. */
    public static final ResourceLocation DESKTOP_ID = DesktopScreens.id("desktop");

    /** Null until resources have loaded (or if the shader failed), in which case plain scaling is used. */
    public static ShaderInstance desktop;

    private DesktopShaders() {}
}
