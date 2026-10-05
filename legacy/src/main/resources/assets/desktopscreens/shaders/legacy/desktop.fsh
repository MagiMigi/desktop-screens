#version 120

// Draws the captured desktop (the same as the newer versions' core/desktop.fsh, in Minecraft 1.12.2's GLSL).
//
// "Sharp" scaling draws each desktop pixel as a solid block (like nearest-neighbour) and only blends
// across the one screen pixel where two desktop pixels meet. Upscaled text stays crisp, without the
// uneven, jagged pixels plain nearest-neighbour gives at sizes like 1.4x. Needs linear filtering.

uniform sampler2D Sampler0;
uniform vec2 TexSize; // desktop size in pixels
uniform vec2 Scale;   // screen pixels per desktop pixel
uniform float Sharp;  // 1 = sharp, 0 = plain bilinear

varying vec2 texCoord0;

void main() {
    vec2 uv = texCoord0;
    if (Sharp > 0.5) {
        vec2 scale = max(Scale, vec2(1.0)); // when shrinking, plain bilinear is already right
        vec2 texel = uv * TexSize;
        vec2 fromCenter = fract(texel) - 0.5;
        vec2 solidPart = 0.5 - 0.5 / scale; // how far from its center a desktop pixel stays one solid color
        vec2 offset = (fromCenter - clamp(fromCenter, -solidPart, solidPart)) * scale + 0.5;
        uv = (floor(texel) + offset) / TexSize;
    }
    // GDI leaves the alpha byte at 0, so ignore it.
    gl_FragColor = vec4(texture2D(Sampler0, uv).rgb, 1.0);
}
