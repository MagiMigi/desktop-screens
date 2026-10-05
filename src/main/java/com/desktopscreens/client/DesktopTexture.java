package com.desktopscreens.client;

//? if >=26.2 {
/*import com.desktopscreens.DesktopScreens;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.BindGroupLayouts;
import org.lwjgl.system.MemoryUtil;
import java.util.Optional;
*///?} else if >=26.1 {
/*import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
*///?} else {
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
//?}
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/**
 * A texture filled straight from the capture's native BGRA memory. It keeps smaller copies (mipmaps), made on the GPU
 * after every upload, so a picture shown much smaller than the monitor (a screen across the room, the settings
 * preview) stays smooth instead of shimmering.
 */
//? if >=26.2 {
/*final class DesktopTexture extends AbstractTexture {
    /^*
     * Copies the picture as captured into the first level. Windows keeps a pixel as blue, green, red and a byte GDI
     * leaves at 0; Minecraft has no texture of that kind, so it arrives read as red, green, blue and alpha, and this
     * swaps them back and makes every pixel opaque (Minecraft's shaders throw away pixels with alpha 0).
     ^/
    private static final RenderPipeline UNPACK = RenderPipeline.builder()
            .withLocation(DesktopScreens.id("pipeline/picture_unpack"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(DesktopScreens.id("core/desktop_unpack"))
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withColorTargetState(ColorTargetState.DEFAULT) // 26.2 assumed it; 26.3 draws no color without one
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();
    /^*
     * Under Vulkan, draws each smaller copy from the one before it with Minecraft's plain copy: smooth, so four pixels
     * make one. (OpenGL makes them itself, see {@link #upload}.)
     ^/
    private static final RenderPipeline SHRINK = RenderPipeline.builder()
            .withLocation(DesktopScreens.id("pipeline/picture_shrink"))
            .withVertexShader("core/screenquad")
            .withFragmentShader("core/blit_screen")
            .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
            .withColorTargetState(ColorTargetState.DEFAULT) // 26.2 assumed it; 26.3 draws no color without one
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();
    private static Boolean unpackWorks;

    private int width, height;
    /^* The picture as captured, before {@link #UNPACK}. ^/
    private GpuTexture raw;
    private GpuTextureView rawView;
    /^* One view per level of {@link #texture}, to draw that level, or (Vulkan) to read it for the next. ^/
    private GpuTextureView[] levels = new GpuTextureView[0];

    DesktopTexture() {
        // Smooth, with the smaller copies, and never wrapping around at the edges.
        sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, true);
    }

    /^*
     * An empty pixel until the first picture. Render thread. Not drawn in practice: pictures show once one came, and
     * its upload made the texture, so nothing has to be made here while something draws (which Vulkan wouldn't allow).
     ^/
    @Override
    public GpuTexture getTexture() {
        if (texture == null) allocate(1, 1);
        return texture;
    }

    @Override
    public GpuTextureView getTextureView() {
        if (textureView == null) allocate(1, 1);
        return textureView;
    }

    /^*
     * Render thread only, outside any drawing (Minecraft takes the world and GUI apart before drawing them, which is
     * when the pictures are asked for). Through Minecraft's own calls, which work with OpenGL and with Vulkan (26.2's
     * new choice in the video settings): the picture is copied as it is, then its colors are put in order on the GPU,
     * and the smaller copies made from it there too.
     ^/
    void upload(long bgraAddress, int w, int h) {
        if (w != width || h != height) allocate(w, h);
        if (!unpackWorks()) return;
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.writeToTexture(raw, MemoryUtil.memByteBuffer(bgraAddress, w * h * 4), 0, 0, 0, 0, w, h);
        draw(encoder, UNPACK, rawView, levels[0], FilterMode.NEAREST);
        if (texture instanceof GlTexture gl) {
            // OpenGL reads a one-level view by narrowing the whole texture to that level (its base and max level),
            // which leaves the next level unfit to draw into ("framebuffer not complete", every picture black on the
            // first test). Its own mipmaps instead, as before 26.2.
            GlStateManager._bindTexture(gl.glId());
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        } else {
            for (int i = 1; i < levels.length; i++) draw(encoder, SHRINK, levels[i - 1], levels[i], FilterMode.LINEAR);
        }
    }

    /^* Fills all of {@code to} from {@code from}, with one triangle that covers it. ^/
    private static void draw(CommandEncoder encoder, RenderPipeline pipeline, GpuTextureView from, GpuTextureView to, FilterMode filter) {
        try (RenderPass pass = encoder.createRenderPass(() -> "Desktop Screens picture", to, Optional.empty())) {
            pass.setPipeline(pipeline);
            pass.bindTexture("InSampler", from, RenderSystem.getSamplerCache().getClampToEdge(filter));
            pass.draw(3, 1, 0, 0);
        }
    }

    /^* Whether our shader compiled; if not, that's said once and nothing is drawn into the texture. ^/
    private static boolean unpackWorks() {
        if (unpackWorks == null) {
            unpackWorks = RenderSystem.getDevice().precompilePipeline(UNPACK).isValid();
            if (!unpackWorks) DesktopScreens.LOG.error("Couldn't compile the shader that unpacks desktop pictures; they won't show");
        }
        return unpackWorks;
    }

    private void allocate(int w, int h) {
        close();
        GpuDevice device = RenderSystem.getDevice();
        int levelCount = 32 - Integer.numberOfLeadingZeros(Math.max(w, h)); // down to 1x1
        texture = device.createTexture(() -> "Desktop Screens picture",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, w, h, 1, levelCount);
        textureView = device.createTextureView(texture);
        levels = new GpuTextureView[levelCount];
        for (int i = 0; i < levelCount; i++) levels[i] = device.createTextureView(texture, i, 1);
        raw = device.createTexture(() -> "Desktop Screens picture (as captured)",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
        rawView = device.createTextureView(raw);
        width = w;
        height = h;
    }

    @Override
    protected void releaseTextures() {
        for (GpuTextureView level : levels) level.close();
        levels = new GpuTextureView[0];
        if (rawView != null) rawView.close();
        if (raw != null) raw.close();
        rawView = null;
        raw = null;
        super.releaseTextures();
    }
}
*///?} else if >=26.1 {
/*final class DesktopTexture extends AbstractTexture {
    private int width, height;

    DesktopTexture() {
        // Smooth, with the smaller copies, and never wrapping around at the edges.
        sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, true);
    }

    /^* A black pixel until the first picture, so whatever draws it before then has something to draw. Render thread. ^/
    @Override
    public GpuTexture getTexture() {
        if (texture == null) allocate(1, 1);
        return texture;
    }

    @Override
    public GpuTextureView getTextureView() {
        if (textureView == null) allocate(1, 1);
        return textureView;
    }

    /^*
     * Render thread only. Blaze3D has no BGRA upload, and converting every picture on the CPU would cost milliseconds,
     * so this writes to its OpenGL texture directly (26.1 always draws with OpenGL; 26.2 adds Vulkan).
     ^/
    void upload(long bgraAddress, int w, int h) {
        if (w != width || h != height) allocate(w, h);
        GlStateManager._bindTexture(((GlTexture) texture).glId());
        // Minecraft's own uploads change these; reset them so rows are read tightly packed.
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        GlStateManager._texSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, w, h, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, bgraAddress);
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
    }

    private void allocate(int w, int h) {
        close();
        GpuDevice device = RenderSystem.getDevice();
        int levels = 32 - Integer.numberOfLeadingZeros(Math.max(w, h)); // down to 1x1
        texture = device.createTexture(() -> "Desktop Screens picture", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                TextureFormat.RGBA8, w, h, 1, levels);
        textureView = device.createTextureView(texture);
        // GDI leaves the alpha byte at 0, and Minecraft's shaders throw away pixels with alpha 0: read it as 1.
        GlStateManager._bindTexture(((GlTexture) texture).glId());
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL33.GL_TEXTURE_SWIZZLE_A, GL11.GL_ONE);
        width = w;
        height = h;
    }
}
*///?} else {
final class DesktopTexture extends AbstractTexture {
    private int width, height;

    @Override
    public void load(ResourceManager manager) {
        // The pixels come from upload(), not from a resource pack.
    }

    /** Render thread only. */
    void upload(long bgraAddress, int w, int h) {
        GlStateManager._bindTexture(getId());
        if (w != width || h != height) allocate(w, h);
        // Minecraft's own uploads change these; reset them so rows are read tightly packed.
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, w, h, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, bgraAddress);
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
    }

    private void allocate(int w, int h) {
        // Minecraft sets the filters again whenever it draws with this texture (the screens in the world use mipmaps).
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        // GDI leaves the alpha byte at 0, and Minecraft's shaders throw away pixels with alpha 0. Read alpha as 1
        // through a swizzle (fast BGRA uploads), or else store no alpha channel at all, which also reads as 1.
        GLCapabilities caps = GL.getCapabilities();
        boolean swizzle = caps.OpenGL33 || caps.GL_ARB_texture_swizzle;
        if (swizzle) GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL33.GL_TEXTURE_SWIZZLE_A, GL11.GL_ONE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, swizzle ? GL11.GL_RGBA8 : GL11.GL_RGB8, w, h, 0,
                GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, 0L);
        width = w;
        height = h;
    }
}
//?}
