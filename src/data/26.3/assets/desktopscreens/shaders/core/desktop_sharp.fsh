#version 330
#extension GL_ARB_separate_shader_objects : require

// Draws the captured desktop with "Sharp" scaling: each desktop pixel is drawn as a solid block (like
// nearest-neighbour) and only blends across the one screen pixel where two desktop pixels meet. Upscaled text stays
// crisp, without the uneven, jagged pixels plain nearest-neighbour gives at sizes like 1.4x. Needs linear filtering.
//
// The desktop's size comes from the texture, and how many screen pixels one desktop pixel covers from how fast the
// picture's coordinates change from one screen pixel to the next, so nothing has to be passed in: 26.1's GUI draws
// everything in batches, with no room for a picture's own settings.

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 texSize = vec2(textureSize(Sampler0, 0));
    vec2 texel = texCoord0 * texSize;
    vec2 scale = max(1.0 / fwidth(texel), vec2(1.0)); // when shrinking, plain bilinear is already right
    vec2 fromCenter = fract(texel) - 0.5;
    vec2 solidPart = 0.5 - 0.5 / scale; // how far from its center a desktop pixel stays one solid color
    vec2 offset = (fromCenter - clamp(fromCenter, -solidPart, solidPart)) * scale + 0.5;
    vec2 uv = (floor(texel) + offset) / texSize;
    // The detail level from the unchanged coordinates: the changed ones jump at every desktop pixel's edge.
    // GDI leaves the alpha byte at 0, so ignore it.
    fragColor = vec4(textureGrad(Sampler0, uv, dFdx(texCoord0), dFdy(texCoord0)).rgb, 1.0);
}
