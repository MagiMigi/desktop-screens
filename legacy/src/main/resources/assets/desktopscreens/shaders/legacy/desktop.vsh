#version 120

// Minecraft 1.12.2 draws with OpenGL's old fixed pipeline: positions and texture coordinates come as it hands them in.

varying vec2 texCoord0;

void main() {
    gl_Position = ftransform();
    texCoord0 = gl_MultiTexCoord0.xy;
}
