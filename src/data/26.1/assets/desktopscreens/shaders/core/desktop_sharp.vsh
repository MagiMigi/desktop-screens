#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// The desktop in the GUI, for desktop_sharp.fsh. The vertices are Minecraft's for GUI pictures (position, UV, color).

in vec3 Position;
in vec2 UV0;

out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    texCoord0 = UV0;
}
