#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// The desktop in the GUI, for desktop_sharp.fsh. The vertices are Minecraft's for GUI pictures (position, UV, color).

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;

layout(location = 0) out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    texCoord0 = UV0;
}
