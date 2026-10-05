package com.desktopscreens.client;

import com.desktopscreens.DesktopScreens;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The desktop picture's shader ({@code shaders/legacy/desktop.*}): sharp scaling, see {@link Scaling}. Minecraft 1.12.2
 * has no shaders of its own for the GUI, so it's compiled here with OpenGL 2.0's calls, on first use. Render thread.
 */
final class DesktopShaders {
    /** 0 before the first try, -1 if it failed (then pictures are drawn plain, see {@link #use}). */
    private static int program;
    private static int texSize, scale, sharp;

    private DesktopShaders() {}

    /**
     * Draws with the desktop shader from now on, until {@link #stop}: the picture's size, screen pixels per picture
     * pixel, and whether to keep it sharp. False if there's no shader; then nothing changed.
     */
    static boolean use(int textureWidth, int textureHeight, float scaleX, float scaleY, boolean keepSharp) {
        if (program == 0) program = compile();
        if (program < 0) return false;
        GL20.glUseProgram(program);
        GL20.glUniform2f(texSize, textureWidth, textureHeight);
        GL20.glUniform2f(scale, scaleX, scaleY);
        GL20.glUniform1f(sharp, keepSharp ? 1 : 0);
        return true;
    }

    static void stop() {
        GL20.glUseProgram(0);
    }

    private static int compile() {
        if (!GLContext.getCapabilities().OpenGL20) {
            DesktopScreens.LOG.warn("No OpenGL 2.0: the desktop is drawn without sharp scaling");
            return -1;
        }
        int vertex = 0, fragment = 0, p = 0;
        try {
            vertex = shader(GL20.GL_VERTEX_SHADER, "desktop.vsh");
            fragment = shader(GL20.GL_FRAGMENT_SHADER, "desktop.fsh");
            p = GL20.glCreateProgram();
            GL20.glAttachShader(p, vertex);
            GL20.glAttachShader(p, fragment);
            GL20.glLinkProgram(p);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
                throw new IllegalStateException("linking: " + GL20.glGetProgramInfoLog(p, 4096));
            }
            GL20.glUseProgram(p);
            GL20.glUniform1i(GL20.glGetUniformLocation(p, "Sampler0"), 0);
            GL20.glUseProgram(0);
            texSize = GL20.glGetUniformLocation(p, "TexSize");
            scale = GL20.glGetUniformLocation(p, "Scale");
            sharp = GL20.glGetUniformLocation(p, "Sharp");
            return p;
        } catch (IOException | RuntimeException e) {
            DesktopScreens.LOG.error("Couldn't make the desktop shader; the desktop is drawn without sharp scaling", e);
            if (p != 0) GL20.glDeleteProgram(p);
            return -1;
        } finally {
            // A linked program keeps what it needs.
            if (vertex != 0) GL20.glDeleteShader(vertex);
            if (fragment != 0) GL20.glDeleteShader(fragment);
        }
    }

    private static int shader(int type, String file) throws IOException {
        String source;
        try (InputStream in = DesktopShaders.class.getResourceAsStream("/assets/desktopscreens/shaders/legacy/" + file)) {
            if (in == null) throw new IOException(file + " is missing from the mod's jar");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            source = new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
            String log = GL20.glGetShaderInfoLog(shader, 4096);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException(file + ": " + log);
        }
        return shader;
    }
}
