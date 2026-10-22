#version 450
#extension GL_GOOGLE_include_directive : require

precision mediump float;
precision highp int;

layout(push_constant) uniform PC {
    float ndcX0;
    float ndcY0;
    float ndcX1;
    float ndcY1;
    int   useTexAlpha;
    float invSrcW;
    float invSrcH;
    float srcW;
    float srcH;
    int   effectId;
    float resW;
    float sharpness;
} pc;

layout(binding = 0) uniform mediump sampler2D texSampler;
layout(location = 0) in  highp vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

float fastLanczos2(float x) {
    float wA = x - 4.0;
    float wB = x * wA - wA;
    wA *= wA;
    return wB * wA;
}

vec2 weightY(float dx, float dy, float c, float std, float spatialFactor) {
    float x = (dx * dx + dy * dy) * spatialFactor + clamp(abs(c) * std, 0.0, 1.0);
    float w = fastLanczos2(x);
    return vec2(w, w * c);
}

#include "postfx_common.glsl"

void applyPostFX(inout vec3 rgb, vec2 uv) {
    rgb = applyPostFXEffect(pc.effectId, rgb, uv, vec2(pc.invSrcW, pc.invSrcH), pc.sharpness, false);
}

void main() {
    highp vec2 step = vec2(pc.invSrcW, pc.invSrcH);

    vec4 center = textureLod(texSampler, fragTexCoord, 0.0);

    highp vec2 imgCoord      = fragTexCoord * vec2(pc.srcW, pc.srcH) + vec2(-0.5, 0.5);
    highp vec2 imgCoordFloor = floor(imgCoord);
    highp vec2 baseUV        = imgCoordFloor * step;
    vec2  pl                 = imgCoord - imgCoordFloor;

    vec4 left = textureGather(texSampler, baseUV, 1);

    float centerG  = center.g;
    float edgeVote = abs(left.z - left.y) + abs(centerG - left.y) + abs(centerG - left.z);

    const float EDGE_THRESHOLD = 12.0 / 255.0;

    if (edgeVote <= EDGE_THRESHOLD) {
        vec3 rgb = center.rgb;
        if (pc.effectId != 0) applyPostFX(rgb, fragTexCoord);
        outColor = vec4(rgb, (pc.useTexAlpha != 0) ? center.a : 1.0);
        return;
    }

    highp vec2 baseUV2 = baseUV + vec2(step.x, 0.0);

    vec4 right;
    right = textureGather(texSampler, baseUV2 + vec2(step.x, 0.0), 1);

    vec4 upDown;
    upDown.xy = textureGather(texSampler, baseUV + vec2(0.0, -step.y), 1).wz;
    upDown.zw = textureGather(texSampler, baseUV + vec2(0.0,  step.y), 1).yx;

    float mean = (left.y + left.z + right.x + right.w) * 0.25;
    left   -= vec4(mean);
    right  -= vec4(mean);
    upDown -= vec4(mean);

    float sum =
        abs(left.x)   + abs(left.y)   + abs(left.z)   + abs(left.w)   +
        abs(right.x)  + abs(right.y)  + abs(right.z)  + abs(right.w)  +
        abs(upDown.x) + abs(upDown.y) + abs(upDown.z) + abs(upDown.w);
    float std = 2.181818 / max(sum, 1.0e-6);

    float spatialFactor = mix(0.40, 0.65, clamp(pc.sharpness, 0.0, 1.0));

    vec2 aWY = weightY(pl.x,       pl.y + 1.0, upDown.x, std, spatialFactor);
    aWY += weightY(pl.x - 1.0, pl.y + 1.0, upDown.y, std, spatialFactor);
    aWY += weightY(pl.x - 1.0, pl.y - 2.0, upDown.z, std, spatialFactor);
    aWY += weightY(pl.x,       pl.y - 2.0, upDown.w, std, spatialFactor);
    aWY += weightY(pl.x + 1.0, pl.y - 1.0, left.x,   std, spatialFactor);
    aWY += weightY(pl.x,       pl.y - 1.0, left.y,   std, spatialFactor);
    aWY += weightY(pl.x,       pl.y,       left.z,   std, spatialFactor);
    aWY += weightY(pl.x + 1.0, pl.y,       left.w,   std, spatialFactor);
    aWY += weightY(pl.x - 1.0, pl.y - 1.0, right.x,  std, spatialFactor);
    aWY += weightY(pl.x - 2.0, pl.y - 1.0, right.y,  std, spatialFactor);
    aWY += weightY(pl.x - 2.0, pl.y,       right.z,  std, spatialFactor);
    aWY += weightY(pl.x - 1.0, pl.y,       right.w,  std, spatialFactor);

    float finalY = aWY.y / max(aWY.x, 1.0e-6);

    float maxY = max(max(left.y, left.z), max(right.x, right.w)) + mean;
    float minY = min(min(left.y, left.z), min(right.x, right.w)) + mean;

    float edgeSharpness = mix(1.0, 2.0, clamp(pc.sharpness, 0.0, 1.0));
    finalY = clamp(edgeSharpness * finalY + mean, minY, maxY);

    float maxDelta = mix(16.0, 40.0, clamp(pc.sharpness, 0.0, 1.0)) / 255.0;
    float deltaY   = clamp(finalY - centerG, -maxDelta, maxDelta);

    vec4 result;
    result.rgb = clamp(center.rgb + vec3(deltaY), 0.0, 1.0);
    result.a   = (pc.useTexAlpha != 0) ? center.a : 1.0;

    if (pc.effectId != 0) applyPostFX(result.rgb, fragTexCoord);

    outColor = result;
}
