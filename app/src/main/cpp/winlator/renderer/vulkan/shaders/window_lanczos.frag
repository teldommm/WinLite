#version 450
#extension GL_GOOGLE_include_directive : require

// Separable Lanczos2 resampling (4x4 / 16 source texels), plus the shared Sharpness slider
// applied as a light unsharp mask on top of the resample result.
// Intended as a high-quality spatial texture filter between bilinear and heavier upscalers.

layout(push_constant) uniform PC {
    float ndcX0;
    float ndcY0;
    float ndcX1;
    float ndcY1;
    int   useTexAlpha;
    float srcW;
    float srcH;
    float outW;
    float outH;
    int   effectId;
    float sharpness;
} pc;

layout(binding = 0) uniform sampler2D texSampler;
layout(location = 0) in vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

const float PI = 3.14159265358979323846;

float sinc(float x) {
    x = abs(x);
    if (x < 1e-5) return 1.0;
    float p = PI * x;
    return sin(p) / p;
}

float lanczos2(float x) {
    x = abs(x);
    if (x >= 2.0) return 0.0;
    return sinc(x) * sinc(x * 0.5);
}


#include "postfx_common.glsl"

void applyPostFX(inout vec3 rgb, vec2 uv) {
    rgb = applyPostFXEffect(pc.effectId, rgb, uv, 1.0 / max(vec2(pc.srcW, pc.srcH), vec2(1.0)), pc.sharpness, false);
}

void main() {
    vec2 srcSize = max(vec2(pc.srcW, pc.srcH), vec2(1.0));
    vec2 srcPos = fragTexCoord * srcSize - vec2(0.5);
    ivec2 base = ivec2(floor(srcPos));
    vec2 fracPart = srcPos - floor(srcPos);

    // The kernel is separable: 4 weights per axis, not one pair per tap (8 evaluations of
    // lanczos2 instead of 20), and the edge clamp computed once.
    vec4 wx, wy;
    for (int i = 0; i < 4; ++i) {
        wx[i] = lanczos2(float(i - 1) - fracPart.x);
        wy[i] = lanczos2(float(i - 1) - fracPart.y);
    }
    ivec2 maxTexel = textureSize(texSampler, 0) - ivec2(1);

    vec4 accum = vec4(0.0);
    for (int y = 0; y < 4; ++y) {
        vec4 row = vec4(0.0);
        for (int x = 0; x < 4; ++x) {
            ivec2 p = clamp(base + ivec2(x - 1, y - 1), ivec2(0), maxTexel);
            row += texelFetch(texSampler, p, 0) * wx[x];
        }
        accum += row * wy[y];
    }
    float totalWeight = dot(wx, vec4(1.0)) * dot(wy, vec4(1.0));

    vec4 color = clamp(accum / max(abs(totalWeight), 1e-6), 0.0, 1.0);
    if (pc.useTexAlpha == 0) color.a = 1.0;

    // Lanczos2's own Sharpness response: a light unsharp mask against a bilinear box blur,
    // same SHARP = sharp * 1.2 response as applyDLS in postfx_common.glsl so the slider feels
    // the same whichever mode is active. This is independent of the Post Effect picker: it
    // runs even when Post Effect is None. DLS under this mode only lifts contrast/saturation
    // (doSharpen = false), so the slider never sharpens twice.
    float sharpAmt = clamp(pc.sharpness, 0.0, 1.0);
    if (sharpAmt > 0.0) {
        vec2 texel = 1.0 / srcSize;
        vec3 blur = (texture(texSampler, fragTexCoord + vec2(texel.x, 0.0)).rgb
                   + texture(texSampler, fragTexCoord - vec2(texel.x, 0.0)).rgb
                   + texture(texSampler, fragTexCoord + vec2(0.0, texel.y)).rgb
                   + texture(texSampler, fragTexCoord - vec2(0.0, texel.y)).rgb) * 0.25;
        color.rgb = clamp(color.rgb + (color.rgb - blur) * (sharpAmt * 1.2), 0.0, 1.0);
    }

    if (pc.effectId != 0) applyPostFX(color.rgb, fragTexCoord);
    outColor = color;
}
