package com.desktopscreens.client;

//? if <26.1 {
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
//? if >=1.21 {
import com.mojang.blaze3d.vertex.MeshData;
//?}
//?}
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

/**
 * Writes vertices the same way in every Minecraft version. 1.21 renamed the calls ({@code addVertex}, {@code setColor},
 * {@code setUv}, {@code setNormal}) and ends each vertex by itself; before it, each vertex ended with
 * {@code endVertex()}, and the shared buffer was started and finished differently.
 */
final class Vertices {
    private Vertices() {}

    //? if <26.1 {
    /** Starts the game's shared buffer; {@link #draw} draws what was added and finishes it. */
    static BufferBuilder begin(VertexFormat.Mode mode, VertexFormat format) {
        //? if >=1.21 {
        return Tesselator.getInstance().begin(mode, format);
        //?} else {
        /*BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(mode, format);
        return buffer;
        *///?}
    }

    /** Draws what was added since {@link #begin} with the current shader; nothing if nothing was. */
    static void draw(BufferBuilder buffer) {
        //? if >=1.21 {
        MeshData mesh = buffer.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
        //?} else {
        /*BufferUploader.drawWithShader(buffer.end());
        *///?}
    }
    //?}

    /** A vertex with a color (ARGB), placed by {@code pose}. */
    static void posColor(VertexConsumer out, Matrix4f pose, float x, float y, float z, int argb) {
        //? if >=1.21 {
        out.addVertex(pose, x, y, z).setColor(argb);
        //?} else {
        /*out.vertex(pose, x, y, z).color(argb).endVertex();
        *///?}
    }

    /** A vertex with a color (ARGB), as it is (in the current model-view space). */
    static void posColor(VertexConsumer out, float x, float y, float z, int argb) {
        //? if >=1.21 {
        out.addVertex(x, y, z).setColor(argb);
        //?} else {
        /*out.vertex(x, y, z).color(argb).endVertex();
        *///?}
    }

    /** A vertex with a color (ARGB) and a normal, as it is. */
    static void posColorNormal(VertexConsumer out, float x, float y, float z, int argb, float nx, float ny, float nz) {
        //? if >=1.21 {
        out.addVertex(x, y, z).setColor(argb).setNormal(nx, ny, nz);
        //?} else {
        /*out.vertex(x, y, z).color(argb).normal(nx, ny, nz).endVertex();
        *///?}
    }

    /**
     * A line's end (a color, its direction as the normal), as it is. 26.1 gives every line its width in pixels; before,
     * the caller sets it for all of them ({@code RenderSystem.lineWidth}).
     */
    static void line(VertexConsumer out, float x, float y, float z, int argb, float nx, float ny, float nz, float width) {
        //? if >=26.1 {
        /*out.addVertex(x, y, z).setColor(argb).setNormal(nx, ny, nz).setLineWidth(width);
        *///?} else {
        posColorNormal(out, x, y, z, argb, nx, ny, nz);
        //?}
    }

    /** A vertex with texture coordinates, placed by {@code pose}. */
    static void posUv(VertexConsumer out, Matrix4f pose, float x, float y, float z, float u, float v) {
        //? if >=1.21 {
        out.addVertex(pose, x, y, z).setUv(u, v);
        //?} else {
        /*out.vertex(pose, x, y, z).uv(u, v).endVertex();
        *///?}
    }
}
