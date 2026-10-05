package com.desktopscreens.client;

import com.sun.jna.Pointer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureUtil;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;

/**
 * A texture filled straight from the capture's native BGRA memory. It keeps smaller copies (mipmaps), made on the GPU
 * after every upload, so a picture shown much smaller than the monitor (the settings preview, a screen across the
 * room) stays smooth instead of shimmering. Render thread only.
 */
final class DesktopTexture {
    private int id = -1;
    private int width, height;
    /** How the smaller copies are made: 0 = not at all (an old graphics card), 1 = OpenGL 3, 2 = ARB, 3 = EXT. */
    private int mipmapper = -1;

    /** The OpenGL texture, made on first use. */
    int id() {
        if (id < 0) id = TextureUtil.glGenTextures();
        return id;
    }

    /** {@code w} x {@code h} BGRA pixels at {@code bgraAddress}, tightly packed. */
    void upload(long bgraAddress, int w, int h) {
        // LWJGL 2 only takes buffers: one over the capture's memory, no copy.
        upload(new Pointer(bgraAddress).getByteBuffer(0, (long) w * h * 4), w, h);
    }

    /** {@code w} x {@code h} BGRA pixels in {@code pixels}, tightly packed, from its position on. */
    void upload(ByteBuffer pixels, int w, int h) {
        GlStateManager.bindTexture(id());
        if (w != width || h != height) allocate(w, h);
        // Minecraft's own uploads change these; reset them so rows are read tightly packed.
        GlStateManager.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, w, h, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, pixels);
        switch (mipmapper) {
            case 1: GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D); break;
            case 2: ARBFramebufferObject.glGenerateMipmap(GL11.GL_TEXTURE_2D); break;
            case 3: EXTFramebufferObject.glGenerateMipmapEXT(GL11.GL_TEXTURE_2D); break;
            default: break;
        }
    }

    private void allocate(int w, int h) {
        ContextCapabilities caps = GLContext.getCapabilities();
        if (mipmapper < 0) {
            mipmapper = caps.OpenGL30 ? 1 : caps.GL_ARB_framebuffer_object ? 2 : caps.GL_EXT_framebuffer_object ? 3 : 0;
        }
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, mipmapper > 0 ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        // GDI leaves the alpha byte at 0, and Minecraft's alpha test throws away such pixels. Read alpha as 1 through
        // a swizzle (fast BGRA uploads), or else store no alpha channel at all, which also reads as 1.
        boolean swizzle = caps.OpenGL33 || caps.GL_ARB_texture_swizzle;
        if (swizzle) GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL33.GL_TEXTURE_SWIZZLE_A, GL11.GL_ONE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, swizzle ? GL11.GL_RGBA8 : GL11.GL_RGB8, w, h, 0,
                GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        width = w;
        height = h;
    }

    void delete() {
        if (id >= 0) TextureUtil.deleteTexture(id);
        id = -1;
        width = height = 0;
    }
}
