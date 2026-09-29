package com.desktopscreens.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * How the desktop picture is drawn on screens in the world: its own texture, no lighting (it glows, like a real
 * screen), smooth when small (mipmaps), and pulled a little toward the viewer so it never flickers with the black
 * glass right behind it. Built from the render states directly, since plain Minecraft keeps {@code RenderType.create}
 * to itself (NeoForge opens it up, Fabric doesn't).
 */
final class PictureRenderType extends RenderType {
    private PictureRenderType(String name, Runnable setup, Runnable clear) {
        super(name, DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS, 1536, false, false, setup, clear);
    }

    static RenderType of(ResourceLocation texture) {
        // In the order Minecraft's own render types set them up.
        List<RenderStateShard> states = List.of(new TextureStateShard(texture, true, true), POSITION_TEX_SHADER, NO_TRANSPARENCY,
                LEQUAL_DEPTH_TEST, NO_CULL, NO_LIGHTMAP, NO_OVERLAY, POLYGON_OFFSET_LAYERING, MAIN_TARGET, DEFAULT_TEXTURING,
                COLOR_DEPTH_WRITE, DEFAULT_LINE);
        return new PictureRenderType("desktopscreens_picture_" + texture.getPath().replace('/', '_'),
                () -> states.forEach(RenderStateShard::setupRenderState),
                () -> states.forEach(RenderStateShard::clearRenderState));
    }
}
