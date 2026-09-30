package com.desktopscreens.client;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLCapabilities;

/**
 * A texture filled straight from the capture's native BGRA memory. It keeps smaller copies (mipmaps), made on the GPU
 * after every upload, so a picture shown much smaller than the monitor (a screen across the room, the settings
 * preview) stays smooth instead of shimmering.
 */
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
