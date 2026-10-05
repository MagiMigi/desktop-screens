package com.desktopscreens.client;

//? if >=26.2 {
/*import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import net.minecraft.client.renderer.BindGroupLayouts;
*///?}
//? if >=26.1 {
/*import com.desktopscreens.DesktopScreens;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
*///?} else {
import net.minecraft.client.renderer.RenderStateShard;
import java.util.List;
//?}
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * How the desktop picture is drawn on screens in the world: its own texture, no lighting (it glows, like a real
 * screen), smooth when small (mipmaps), and pulled a little toward the viewer so it never flickers with the black
 * glass right behind it. Only its front side: pulled like that, its back showed through the block from behind at a
 * slant (seen on 1.12.2, 2026-10-04; the quads go round counter-clockwise seen from the front). Built from the render
 * states directly, since plain Minecraft keeps {@code RenderType.create}
 * to itself (NeoForge opens it up, Fabric doesn't).
 */
//? if >=26.2 {
/*final class PictureRenderType {
    /^*
     * Minecraft's plain textured shader, front side only, pulled toward the viewer as the cracks of a block being broken are.
     * 26.2 turned depth around (near is 1, far is 0), and the test and the pull with it.
     ^/
    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(DesktopScreens.id("pipeline/picture"))
            .withVertexShader("core/position_tex")
            .withFragmentShader("core/position_tex")
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withColorTargetState(ColorTargetState.DEFAULT) // 26.2 assumed it; 26.3 draws no color without one
            .withCull(true)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true, 1.0F, 10.0F))
            .build();

    private PictureRenderType() {}

    static RenderType of(ResourceLocation texture) {
        return RenderType.create("desktopscreens_picture_" + texture.getPath().replace('/', '_'), RenderSetup.builder(PIPELINE)
                .withTexture("Sampler0", texture, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, true))
                .createRenderSetup());
    }
}
*///?} else if >=26.1 {
/*final class PictureRenderType {
    /^* Minecraft's plain textured shader, front side only, pulled toward the viewer as the cracks of a block being broken are. ^/
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
            .withLocation(DesktopScreens.id("pipeline/picture"))
            .withVertexShader("core/position_tex")
            .withFragmentShader("core/position_tex")
            .withSampler("Sampler0")
            .withCull(true)
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS)
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, -1.0F, -10.0F))
            .build();

    private PictureRenderType() {}

    static RenderType of(ResourceLocation texture) {
        return RenderType.create("desktopscreens_picture_" + texture.getPath().replace('/', '_'), RenderSetup.builder(PIPELINE)
                .withTexture("Sampler0", texture, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, true))
                .createRenderSetup());
    }
}
*///?} else {
final class PictureRenderType extends RenderType {
    private PictureRenderType(String name, Runnable setup, Runnable clear) {
        super(name, DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS, 1536, false, false, setup, clear);
    }

    static RenderType of(ResourceLocation texture) {
        // In the order Minecraft's own render types set them up.
        List<RenderStateShard> states = List.of(new TextureStateShard(texture, true, true), POSITION_TEX_SHADER, NO_TRANSPARENCY,
                LEQUAL_DEPTH_TEST, CULL, NO_LIGHTMAP, NO_OVERLAY, POLYGON_OFFSET_LAYERING, MAIN_TARGET, DEFAULT_TEXTURING,
                COLOR_DEPTH_WRITE, DEFAULT_LINE);
        return new PictureRenderType("desktopscreens_picture_" + texture.getPath().replace('/', '_'),
                () -> states.forEach(RenderStateShard::setupRenderState),
                () -> states.forEach(RenderStateShard::clearRenderState));
    }
}
//?}
