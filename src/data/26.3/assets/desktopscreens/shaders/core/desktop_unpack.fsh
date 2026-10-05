#version 330
#extension GL_ARB_separate_shader_objects : require

// Copies a captured picture into the first level of the texture that's drawn (DesktopTexture). Windows keeps a pixel
// as blue, green, red and a byte GDI leaves at 0; Minecraft has no texture of that kind, so it arrives here read as
// red, green, blue and alpha. Swap them back and make every pixel opaque: Minecraft's shaders throw away pixels with
// alpha 0. The picture is the same size as what this draws, so each pixel lands exactly on one.

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = vec4(texture(InSampler, texCoord).bgr, 1.0);
}
