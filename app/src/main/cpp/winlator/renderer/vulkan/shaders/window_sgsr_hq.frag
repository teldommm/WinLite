#version 450
#extension GL_GOOGLE_include_directive : require

// =============================================================================
//  Snapdragon Game Super Resolution (SGSR) 1 - "HQ" variant (edge direction).
//
//  Ported from Qualcomm's reference shader:
//    SnapdragonGameStudios/snapdragon-gsr
//    sgsr/v1/include/glsl/sgsr1_shader_mobile_edge_direction.frag
//  Copyright (c) 2025, Qualcomm Innovation Center, Inc. All rights reserved.
//  SPDX-License-Identifier: BSD-3-Clause
//
//  Same single pass and push-constant layout as window_sgsr.frag. Differences:
//   - the Lanczos weights are stretched along the local edge direction, so
//     diagonal edges reconstruct cleaner;
//   - the reference's retuned contrast term std = (10.14185 / sum)^2;
//   - edge threshold 8/255; sharpness response matches window_sgsr.frag.
//  The post effects (DLS / CRT / HDR / Natural) are kept, like in window_sgsr.frag.
//
//  Two precisions, same source (mirrors Qualcomm's fp16 reference build):
//  - default: full-precision (highp) maths.
//  - BL_HALF (the window_sgsr_hq_half build): colour and weight maths in mediump
//    (RelaxedPrecision, 16-bit on Adreno, ~2x fragment throughput); positions,
//    texture coordinates and push constants stay highp. Two hazards move to fp16
//    with the maths, both taken from the reference's *_half build:
//     1. (10.14 / sum)^2 passes 65504 (16-bit max) once sum < ~0.04 and
//        c * c * inf is NaN for c == 0 -> the squared term is capped;
//     2. 3.075740e-05 is below the smallest normal 16-bit float (6.1e-05) and
//        flushes to 0 where denormals are not kept: 0 * inf on a flat row -> the
//        inversesqrt of the edge direction runs in highp.
// =============================================================================

// BL_HALF is set for the window_sgsr_hq_half_frag build (CMakeLists passes -DBL_HALF).
#ifdef BL_HALF
precision mediump float;
#define BL_MP mediump
#else
#define BL_MP
#endif

layout(push_constant) uniform PC {
    highp float ndcX0;
    highp float ndcY0;
    highp float ndcX1;
    highp float ndcY1;
    highp int   useTexAlpha;
    highp float invSrcW;
    highp float invSrcH;
    highp float srcW;
    highp float srcH;
    highp int   effectId;
    highp float resW;
    highp float sharpness;
} pc;

layout(binding = 0) uniform BL_MP sampler2D texSampler;
layout(location = 0) in  highp vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

const float EDGE_THRESHOLD = 8.0 / 255.0;

float fastLanczos2(float x) {
    float wA = x - 4.0;
    float wB = x * wA - wA;
    wA *= wA;
    return wB * wA;
}

// data.x = std, data.yz = normalised edge direction
vec2 weightY(float dx, float dy, float c, vec3 data) {
    float std = data.x;
    vec2  dir = data.yz;
    float edgeDis = (dx * dir.y) + (dy * dir.x);
    float x = ((dx * dx) + (dy * dy))
            + ((edgeDis * edgeDis) * ((clamp((c * c) * std, 0.0, 1.0) * 0.7) - 1.0));
    float w = fastLanczos2(x);
    return vec2(w, w * c);
}

vec2 edgeDirection(vec4 left, vec4 right) {
    float RxLz = right.x - left.z;
    float RwLy = right.w - left.y;
    vec2 delta;
    delta.x = RxLz + RwLy;
    delta.y = RxLz - RwLy;
#ifdef BL_HALF
    // The epsilon is below the smallest normal fp16 value; squared differences of
    // two mediump gathers can also round to 0. The sum runs in highp so that a
    // perfectly flat row keeps its well-defined 0 * sqrt(3.07e-05) direction.
    highp vec2 dh = delta;
    highp float lengthInv = inversesqrt((dh.x * dh.x + 3.075740e-05) + (dh.y * dh.y));
#else
    float lengthInv = inversesqrt((delta.x * delta.x + 3.075740e-05) + (delta.y * delta.y));
#endif
    return delta * lengthInv;
}

#include "postfx_common.glsl"

void applyPostFX(inout vec3 rgb, vec2 uv) {
    rgb = applyPostFXEffect(pc.effectId, rgb, uv, vec2(pc.invSrcW, pc.invSrcH), pc.sharpness, false);
}

void main() {
    // The coordinate chain stays highp in both builds: srcW/srcH scale uv up to
    // full texel units, where fp16 has too little mantissa for a stable floor().
    highp vec2 step = vec2(pc.invSrcW, pc.invSrcH);

    vec4 color;
    color.xyz = textureLod(texSampler, fragTexCoord, 0.0).xyz;
    float centerAlpha = textureLod(texSampler, fragTexCoord, 0.0).a;

    highp vec2 imgCoord      = fragTexCoord * vec2(pc.srcW, pc.srcH) + vec2(-0.5, 0.5);
    highp vec2 imgCoordPixel = floor(imgCoord);
    highp vec2 coord         = imgCoordPixel * step;
    vec2  pl                 = imgCoord - imgCoordPixel;
    vec4 left          = textureGather(texSampler, coord, 1);

    float edgeVote = abs(left.z - left.y) + abs(color.y - left.y) + abs(color.y - left.z);
    if (edgeVote > EDGE_THRESHOLD) {
        coord.x += step.x;

        vec4 right = textureGather(texSampler, coord + vec2(step.x, 0.0), 1);
        vec4 upDown;
        upDown.xy = textureGather(texSampler, coord + vec2(0.0, -step.y), 1).wz;
        upDown.zw = textureGather(texSampler, coord + vec2(0.0,  step.y), 1).yx;

        float mean = (left.y + left.z + right.x + right.w) * 0.25;
        left   -= vec4(mean);
        right  -= vec4(mean);
        upDown -= vec4(mean);
        color.w = color.y - mean;

        float sum = abs(left.x)   + abs(left.y)   + abs(left.z)   + abs(left.w)
                  + abs(right.x)  + abs(right.y)  + abs(right.z)  + abs(right.w)
                  + abs(upDown.x) + abs(upDown.y) + abs(upDown.z) + abs(upDown.w);
        float sumMean = 10.14185 / max(sum, 1.0e-6);
#ifdef BL_HALF
        // min() absorbs inf: (10.14 / sum)^2 overflows fp16 once sum < ~0.04, and
        // c * c * inf is NaN for c == 0 inside weightY. Capped: c * c * 65504 still
        // saturates the clamp in weightY for any c >= 1/255, like the reference's
        // fp16 build.
        float std = min(sumMean * sumMean, 65504.0);
#else
        float std = sumMean * sumMean;
#endif

        vec3 data = vec3(std, edgeDirection(left, right));

        vec2 aWY  = weightY(pl.x,       pl.y + 1.0, upDown.x, data);
        aWY      += weightY(pl.x - 1.0, pl.y + 1.0, upDown.y, data);
        aWY      += weightY(pl.x - 1.0, pl.y - 2.0, upDown.z, data);
        aWY      += weightY(pl.x,       pl.y - 2.0, upDown.w, data);
        aWY      += weightY(pl.x + 1.0, pl.y - 1.0, left.x,   data);
        aWY      += weightY(pl.x,       pl.y - 1.0, left.y,   data);
        aWY      += weightY(pl.x,       pl.y,       left.z,   data);
        aWY      += weightY(pl.x + 1.0, pl.y,       left.w,   data);
        aWY      += weightY(pl.x - 1.0, pl.y - 1.0, right.x,  data);
        aWY      += weightY(pl.x - 2.0, pl.y - 1.0, right.y,  data);
        aWY      += weightY(pl.x - 2.0, pl.y,       right.z,  data);
        aWY      += weightY(pl.x - 1.0, pl.y,       right.w,  data);

        float finalY = aWY.y / max(aWY.x, 1.0e-6);

        float maxY = max(max(left.y, left.z), max(right.x, right.w));
        float minY = min(min(left.y, left.z), min(right.x, right.w));

        // Same sharpness response as window_sgsr.frag, so the slider feels identical in both modes:
        // EdgeSharpness 1.0 .. 2.0, delta clamp 16/255 .. 40/255.
        float sharp = clamp(pc.sharpness, 0.0, 1.0);
        float edgeSharpness = mix(1.0, 2.0, sharp);
        finalY = clamp(edgeSharpness * finalY, minY, maxY);

        float maxDelta = mix(16.0, 40.0, sharp) / 255.0;
        float deltaY = clamp(finalY - color.w, -maxDelta, maxDelta);
        color.xyz = clamp(color.xyz + vec3(deltaY), 0.0, 1.0);
    }

    vec3 rgb = color.xyz;
    if (pc.effectId != 0) applyPostFX(rgb, fragTexCoord);
    outColor = vec4(rgb, (pc.useTexAlpha != 0) ? centerAlpha : 1.0);
}
