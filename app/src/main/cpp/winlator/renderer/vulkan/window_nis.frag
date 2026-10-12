#version 450

// =============================================================================
//  NVIDIA Image Scaling (NIS) - NVScaler spatial upscaler + adaptive sharpen.
//
//  Ported from NVIDIA's reference NVScaler:
//    NVIDIAGameWorks/NVIDIAImageScaling  NIS/NIS_Scaler.h  (+ NIS_Config.h)
//
//  The MIT License (MIT)
//  Copyright (c) 2022 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
//  SPDX-License-Identifier: MIT
//
//  Single-pass FRAGMENT port (the reference is an LDS-tiled compute shader):
//   - the 6x6 luma support is fetched per fragment, the edge map is derived
//     inline; the maths (edge detection + 6-tap directional scale/USM blend)
//     is the reference's;
//   - the 64-phase scaler / USM coefficient banks (fp32 path) are baked in;
//   - SDR (HDR mode None) constants, sharpness-derived parameters recomputed
//     in-shader exactly like NVScalerUpdateConfig from the Sharpness slider
//     (0.5 = neutral);
//   - same push-constant layout as window_sgsr.frag; post effects
//     (DLS / CRT / HDR / Natural) are kept.
// =============================================================================

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

layout(binding = 0) uniform sampler2D texSampler;
layout(location = 0) in  vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

// ---- SDR (HDR mode None) constants, from NVScalerUpdateConfig -----------------
const float kDetectRatio      = 2.0 * 1127.0 / 1024.0; // 2.201171875
const float kDetectThres      = 64.0 / 1024.0;         // 0.0625
const float kMinContrastRatio = 2.0;
const float kMaxContrastRatio = 10.0;
const float kRatioNorm        = 1.0 / (kMaxContrastRatio - kMinContrastRatio); // 0.125
const float kContrastBoost    = 1.0;
const float kEps              = 1.0 / 255.0;
const float kSharpStartY      = 0.45;
const float kSharpEndY        = 0.9;
const float kSharpScaleY      = 1.0 / (kSharpEndY - kSharpStartY); // 2.222...
const int   kPhaseCount       = 64;

// Sharpness-derived params (recomputed in main from pc.sharpness, read here).
float gSharpStrengthMin;
float gSharpStrengthScale;
float gSharpLimitMin;
float gSharpLimitScale;

const float coefScale[384] = float[384](
    0.0, 0.0, 1.0000, 0.0, 0.0, 0.0,   // phase 0
    0.0029, -0.0127, 1.0000, 0.0132, -0.0034, 0.0,   // phase 1
    0.0063, -0.0249, 0.9985, 0.0269, -0.0068, 0.0,   // phase 2
    0.0088, -0.0361, 0.9956, 0.0415, -0.0103, 0.0005,   // phase 3
    0.0117, -0.0474, 0.9932, 0.0562, -0.0142, 0.0005,   // phase 4
    0.0142, -0.0576, 0.9897, 0.0713, -0.0181, 0.0005,   // phase 5
    0.0166, -0.0674, 0.9844, 0.0874, -0.0220, 0.0010,   // phase 6
    0.0186, -0.0762, 0.9785, 0.1040, -0.0264, 0.0015,   // phase 7
    0.0205, -0.0850, 0.9727, 0.1206, -0.0308, 0.0020,   // phase 8
    0.0225, -0.0928, 0.9648, 0.1382, -0.0352, 0.0024,   // phase 9
    0.0239, -0.1006, 0.9575, 0.1558, -0.0396, 0.0029,   // phase 10
    0.0254, -0.1074, 0.9487, 0.1738, -0.0439, 0.0034,   // phase 11
    0.0264, -0.1138, 0.9390, 0.1929, -0.0488, 0.0044,   // phase 12
    0.0278, -0.1191, 0.9282, 0.2119, -0.0537, 0.0049,   // phase 13
    0.0288, -0.1245, 0.9170, 0.2310, -0.0581, 0.0059,   // phase 14
    0.0293, -0.1294, 0.9058, 0.2510, -0.0630, 0.0063,   // phase 15
    0.0303, -0.1333, 0.8926, 0.2710, -0.0679, 0.0073,   // phase 16
    0.0308, -0.1367, 0.8789, 0.2915, -0.0728, 0.0083,   // phase 17
    0.0308, -0.1401, 0.8657, 0.3120, -0.0776, 0.0093,   // phase 18
    0.0313, -0.1426, 0.8506, 0.3330, -0.0825, 0.0103,   // phase 19
    0.0313, -0.1445, 0.8354, 0.3540, -0.0874, 0.0112,   // phase 20
    0.0313, -0.1460, 0.8193, 0.3755, -0.0923, 0.0122,   // phase 21
    0.0313, -0.1470, 0.8022, 0.3965, -0.0967, 0.0137,   // phase 22
    0.0308, -0.1479, 0.7856, 0.4185, -0.1016, 0.0146,   // phase 23
    0.0303, -0.1479, 0.7681, 0.4399, -0.1060, 0.0156,   // phase 24
    0.0298, -0.1479, 0.7505, 0.4614, -0.1104, 0.0166,   // phase 25
    0.0293, -0.1470, 0.7314, 0.4829, -0.1147, 0.0181,   // phase 26
    0.0288, -0.1460, 0.7119, 0.5049, -0.1187, 0.0190,   // phase 27
    0.0278, -0.1445, 0.6929, 0.5264, -0.1226, 0.0200,   // phase 28
    0.0273, -0.1431, 0.6724, 0.5479, -0.1260, 0.0215,   // phase 29
    0.0264, -0.1411, 0.6528, 0.5693, -0.1299, 0.0225,   // phase 30
    0.0254, -0.1387, 0.6323, 0.5903, -0.1328, 0.0234,   // phase 31
    0.0244, -0.1357, 0.6113, 0.6113, -0.1357, 0.0244,   // phase 32
    0.0234, -0.1328, 0.5903, 0.6323, -0.1387, 0.0254,   // phase 33
    0.0225, -0.1299, 0.5693, 0.6528, -0.1411, 0.0264,   // phase 34
    0.0215, -0.1260, 0.5479, 0.6724, -0.1431, 0.0273,   // phase 35
    0.0200, -0.1226, 0.5264, 0.6929, -0.1445, 0.0278,   // phase 36
    0.0190, -0.1187, 0.5049, 0.7119, -0.1460, 0.0288,   // phase 37
    0.0181, -0.1147, 0.4829, 0.7314, -0.1470, 0.0293,   // phase 38
    0.0166, -0.1104, 0.4614, 0.7505, -0.1479, 0.0298,   // phase 39
    0.0156, -0.1060, 0.4399, 0.7681, -0.1479, 0.0303,   // phase 40
    0.0146, -0.1016, 0.4185, 0.7856, -0.1479, 0.0308,   // phase 41
    0.0137, -0.0967, 0.3965, 0.8022, -0.1470, 0.0313,   // phase 42
    0.0122, -0.0923, 0.3755, 0.8193, -0.1460, 0.0313,   // phase 43
    0.0112, -0.0874, 0.3540, 0.8354, -0.1445, 0.0313,   // phase 44
    0.0103, -0.0825, 0.3330, 0.8506, -0.1426, 0.0313,   // phase 45
    0.0093, -0.0776, 0.3120, 0.8657, -0.1401, 0.0308,   // phase 46
    0.0083, -0.0728, 0.2915, 0.8789, -0.1367, 0.0308,   // phase 47
    0.0073, -0.0679, 0.2710, 0.8926, -0.1333, 0.0303,   // phase 48
    0.0063, -0.0630, 0.2510, 0.9058, -0.1294, 0.0293,   // phase 49
    0.0059, -0.0581, 0.2310, 0.9170, -0.1245, 0.0288,   // phase 50
    0.0049, -0.0537, 0.2119, 0.9282, -0.1191, 0.0278,   // phase 51
    0.0044, -0.0488, 0.1929, 0.9390, -0.1138, 0.0264,   // phase 52
    0.0034, -0.0439, 0.1738, 0.9487, -0.1074, 0.0254,   // phase 53
    0.0029, -0.0396, 0.1558, 0.9575, -0.1006, 0.0239,   // phase 54
    0.0024, -0.0352, 0.1382, 0.9648, -0.0928, 0.0225,   // phase 55
    0.0020, -0.0308, 0.1206, 0.9727, -0.0850, 0.0205,   // phase 56
    0.0015, -0.0264, 0.1040, 0.9785, -0.0762, 0.0186,   // phase 57
    0.0010, -0.0220, 0.0874, 0.9844, -0.0674, 0.0166,   // phase 58
    0.0005, -0.0181, 0.0713, 0.9897, -0.0576, 0.0142,   // phase 59
    0.0005, -0.0142, 0.0562, 0.9932, -0.0474, 0.0117,   // phase 60
    0.0005, -0.0103, 0.0415, 0.9956, -0.0361, 0.0088,   // phase 61
    0.0, -0.0068, 0.0269, 0.9985, -0.0249, 0.0063,   // phase 62
    0.0, -0.0034, 0.0132, 1.0000, -0.0127, 0.0029   // phase 63
);

const float coefUsm[384] = float[384](
    0.0, -0.6001, 1.2002, -0.6001, 0.0, 0.0,   // phase 0
    0.0029, -0.6084, 1.1987, -0.5903, -0.0029, 0.0,   // phase 1
    0.0049, -0.6147, 1.1958, -0.5791, -0.0068, 0.0005,   // phase 2
    0.0073, -0.6196, 1.1890, -0.5659, -0.0103, 0.0,   // phase 3
    0.0093, -0.6235, 1.1802, -0.5513, -0.0151, 0.0,   // phase 4
    0.0112, -0.6265, 1.1699, -0.5352, -0.0195, 0.0005,   // phase 5
    0.0122, -0.6270, 1.1582, -0.5181, -0.0259, 0.0005,   // phase 6
    0.0142, -0.6284, 1.1455, -0.5005, -0.0317, 0.0005,   // phase 7
    0.0156, -0.6265, 1.1274, -0.4790, -0.0386, 0.0005,   // phase 8
    0.0166, -0.6235, 1.1089, -0.4570, -0.0454, 0.0010,   // phase 9
    0.0176, -0.6187, 1.0879, -0.4346, -0.0532, 0.0010,   // phase 10
    0.0181, -0.6138, 1.0659, -0.4102, -0.0615, 0.0015,   // phase 11
    0.0190, -0.6069, 1.0405, -0.3843, -0.0698, 0.0015,   // phase 12
    0.0195, -0.6006, 1.0161, -0.3574, -0.0796, 0.0020,   // phase 13
    0.0200, -0.5928, 0.9893, -0.3286, -0.0898, 0.0024,   // phase 14
    0.0200, -0.5820, 0.9580, -0.2988, -0.1001, 0.0029,   // phase 15
    0.0200, -0.5728, 0.9292, -0.2690, -0.1104, 0.0034,   // phase 16
    0.0200, -0.5620, 0.8975, -0.2368, -0.1226, 0.0039,   // phase 17
    0.0205, -0.5498, 0.8643, -0.2046, -0.1343, 0.0044,   // phase 18
    0.0200, -0.5371, 0.8301, -0.1709, -0.1465, 0.0049,   // phase 19
    0.0195, -0.5239, 0.7944, -0.1367, -0.1587, 0.0054,   // phase 20
    0.0195, -0.5107, 0.7598, -0.1021, -0.1724, 0.0059,   // phase 21
    0.0190, -0.4966, 0.7231, -0.0649, -0.1865, 0.0063,   // phase 22
    0.0186, -0.4819, 0.6846, -0.0288, -0.1997, 0.0068,   // phase 23
    0.0186, -0.4668, 0.6460, 0.0093, -0.2144, 0.0073,   // phase 24
    0.0176, -0.4507, 0.6055, 0.0479, -0.2290, 0.0083,   // phase 25
    0.0171, -0.4370, 0.5693, 0.0859, -0.2446, 0.0088,   // phase 26
    0.0161, -0.4199, 0.5283, 0.1255, -0.2598, 0.0098,   // phase 27
    0.0161, -0.4048, 0.4883, 0.1655, -0.2754, 0.0103,   // phase 28
    0.0151, -0.3887, 0.4497, 0.2041, -0.2910, 0.0107,   // phase 29
    0.0142, -0.3711, 0.4072, 0.2446, -0.3066, 0.0117,   // phase 30
    0.0137, -0.3555, 0.3672, 0.2852, -0.3228, 0.0122,   // phase 31
    0.0132, -0.3394, 0.3262, 0.3262, -0.3394, 0.0132,   // phase 32
    0.0122, -0.3228, 0.2852, 0.3672, -0.3555, 0.0137,   // phase 33
    0.0117, -0.3066, 0.2446, 0.4072, -0.3711, 0.0142,   // phase 34
    0.0107, -0.2910, 0.2041, 0.4497, -0.3887, 0.0151,   // phase 35
    0.0103, -0.2754, 0.1655, 0.4883, -0.4048, 0.0161,   // phase 36
    0.0098, -0.2598, 0.1255, 0.5283, -0.4199, 0.0161,   // phase 37
    0.0088, -0.2446, 0.0859, 0.5693, -0.4370, 0.0171,   // phase 38
    0.0083, -0.2290, 0.0479, 0.6055, -0.4507, 0.0176,   // phase 39
    0.0073, -0.2144, 0.0093, 0.6460, -0.4668, 0.0186,   // phase 40
    0.0068, -0.1997, -0.0288, 0.6846, -0.4819, 0.0186,   // phase 41
    0.0063, -0.1865, -0.0649, 0.7231, -0.4966, 0.0190,   // phase 42
    0.0059, -0.1724, -0.1021, 0.7598, -0.5107, 0.0195,   // phase 43
    0.0054, -0.1587, -0.1367, 0.7944, -0.5239, 0.0195,   // phase 44
    0.0049, -0.1465, -0.1709, 0.8301, -0.5371, 0.0200,   // phase 45
    0.0044, -0.1343, -0.2046, 0.8643, -0.5498, 0.0205,   // phase 46
    0.0039, -0.1226, -0.2368, 0.8975, -0.5620, 0.0200,   // phase 47
    0.0034, -0.1104, -0.2690, 0.9292, -0.5728, 0.0200,   // phase 48
    0.0029, -0.1001, -0.2988, 0.9580, -0.5820, 0.0200,   // phase 49
    0.0024, -0.0898, -0.3286, 0.9893, -0.5928, 0.0200,   // phase 50
    0.0020, -0.0796, -0.3574, 1.0161, -0.6006, 0.0195,   // phase 51
    0.0015, -0.0698, -0.3843, 1.0405, -0.6069, 0.0190,   // phase 52
    0.0015, -0.0615, -0.4102, 1.0659, -0.6138, 0.0181,   // phase 53
    0.0010, -0.0532, -0.4346, 1.0879, -0.6187, 0.0176,   // phase 54
    0.0010, -0.0454, -0.4570, 1.1089, -0.6235, 0.0166,   // phase 55
    0.0005, -0.0386, -0.4790, 1.1274, -0.6265, 0.0156,   // phase 56
    0.0005, -0.0317, -0.5005, 1.1455, -0.6284, 0.0142,   // phase 57
    0.0005, -0.0259, -0.5181, 1.1582, -0.6270, 0.0122,   // phase 58
    0.0005, -0.0195, -0.5352, 1.1699, -0.6265, 0.0112,   // phase 59
    0.0, -0.0151, -0.5513, 1.1802, -0.6235, 0.0093,   // phase 60
    0.0, -0.0103, -0.5659, 1.1890, -0.6196, 0.0073,   // phase 61
    0.0005, -0.0068, -0.5791, 1.1958, -0.6147, 0.0049,   // phase 62
    0.0, -0.0029, -0.5903, 1.1987, -0.6084, 0.0029   // phase 63
);

float getY(vec3 rgb) {
    return 0.2126 * rgb.x + 0.7152 * rgb.y + 0.0722 * rgb.z;
}

// GetEdgeMap on a 3x3 luma window (pRC: R=row 0=top, C=col 0=left).
vec4 edgeAt(float p00, float p01, float p02,
            float p10, float p11, float p12,
            float p20, float p21, float p22) {
    float g_0   = abs(p00 + p01 + p02 - p20 - p21 - p22);
    float g_45  = abs(p10 + p00 + p01 - p21 - p22 - p12);
    float g_90  = abs(p00 + p10 + p20 - p02 - p12 - p22);
    float g_135 = abs(p10 + p20 + p21 - p01 - p02 - p12);

    float g_0_90_max   = max(g_0, g_90);
    float g_0_90_min   = min(g_0, g_90);
    float g_45_135_max = max(g_45, g_135);
    float g_45_135_min = min(g_45, g_135);

    if (g_0_90_max + g_45_135_max == 0.0) {
        return vec4(0.0);
    }

    float e_0_90   = min(g_0_90_max / (g_0_90_max + g_45_135_max), 1.0);
    float e_45_135 = 1.0 - e_0_90;

    bool c_0_90    = (g_0_90_max > (g_0_90_min * kDetectRatio)) && (g_0_90_max > kDetectThres) && (g_0_90_max > g_45_135_min);
    bool c_45_135  = (g_45_135_max > (g_45_135_min * kDetectRatio)) && (g_45_135_max > kDetectThres) && (g_45_135_max > g_0_90_min);
    bool c_g_0_90  = (g_0_90_max == g_0);
    bool c_g_45_135= (g_45_135_max == g_45);

    float f_e_0_90   = (c_0_90 && c_45_135) ? e_0_90 : 1.0;
    float f_e_45_135 = (c_0_90 && c_45_135) ? e_45_135 : 1.0;

    float w0   = (c_0_90 && c_g_0_90)     ? f_e_0_90   : 0.0;
    float w90  = (c_0_90 && !c_g_0_90)    ? f_e_0_90   : 0.0;
    float w45  = (c_45_135 && c_g_45_135) ? f_e_45_135 : 0.0;
    float w135 = (c_45_135 && !c_g_45_135)? f_e_45_135 : 0.0;

    return vec4(w0, w90, w45, w135);
}

vec4 getInterpEdgeMap(vec4 e00, vec4 e01, vec4 e10, vec4 e11, float fx, float fy) {
    vec4 h0 = mix(e00, e01, fx);
    vec4 h1 = mix(e10, e11, fx);
    return mix(h0, h1, fy);
}

float calcLTI(float p0, float p1, float p2, float p3, float p4, float p5, int phase_index) {
    bool selector = (phase_index <= kPhaseCount / 2);
    float sel = selector ? p0 : p3;
    float a_min = min(min(p1, p2), sel);
    float a_max = max(max(p1, p2), sel);
    sel = selector ? p2 : p5;
    float b_min = min(min(p3, p4), sel);
    float b_max = max(max(p3, p4), sel);

    float a_cont = a_max - a_min;
    float b_cont = b_max - b_min;

    float cont_ratio = max(a_cont, b_cont) / (min(a_cont, b_cont) + kEps);
    return (1.0 - clamp((cont_ratio - kMinContrastRatio) * kRatioNorm, 0.0, 1.0)) * kContrastBoost;
}

float evalPoly6(float pxl[6], int phase_int) {
    phase_int = clamp(phase_int, 0, 63);
    float y = 0.0;
    for (int i = 0; i < 6; ++i) {
        y += coefScale[phase_int * 6 + i] * pxl[i];
    }
    float y_usm = 0.0;
    for (int i = 0; i < 6; ++i) {
        y_usm += coefUsm[phase_int * 6 + i] * pxl[i];
    }

    // piece-wise ramp based on luma (NIS_SCALE_FLOAT == 1)
    float y_scale = 1.0 - clamp((y - kSharpStartY) * kSharpScaleY, 0.0, 1.0);
    float y_sharpness = y_scale * gSharpStrengthScale + gSharpStrengthMin;
    y_usm *= y_sharpness;

    float y_sharpness_limit = (y_scale * gSharpLimitScale + gSharpLimitMin) * y;
    y_usm = min(y_sharpness_limit, max(-y_sharpness_limit, y_usm));
    y_usm *= calcLTI(pxl[0], pxl[1], pxl[2], pxl[3], pxl[4], pxl[5], phase_int);

    return y + y_usm;
}

float filterNormal(float p[6][6], int phase_x_int, int phase_y_int) {
    phase_x_int = clamp(phase_x_int, 0, 63);
    phase_y_int = clamp(phase_y_int, 0, 63);
    float h_acc = 0.0;
    for (int j = 0; j < 6; ++j) {
        float v_acc = 0.0;
        for (int i = 0; i < 6; ++i) {
            v_acc += p[i][j] * coefScale[phase_y_int * 6 + i];
        }
        h_acc += v_acc * coefScale[phase_x_int * 6 + j];
    }
    return h_acc;
}

float addDirFilters(float p[6][6], float phase_x_frac, float phase_y_frac,
                    int phase_x_frac_int, int phase_y_frac_int, vec4 w) {
    float f = 0.0;
    if (w.x > 0.0) {
        // 0 deg filter
        float interp0Deg[6];
        for (int i = 0; i < 6; ++i) {
            interp0Deg[i] = mix(p[i][2], p[i][3], phase_x_frac);
        }
        f += evalPoly6(interp0Deg, phase_y_frac_int) * w.x;
    }
    if (w.y > 0.0) {
        // 90 deg filter
        float interp90Deg[6];
        for (int i = 0; i < 6; ++i) {
            interp90Deg[i] = mix(p[2][i], p[3][i], phase_y_frac);
        }
        f += evalPoly6(interp90Deg, phase_x_frac_int) * w.y;
    }
    if (w.z > 0.0) {
        // 45 deg filter
        float pphase_b45 = 0.5 + 0.5 * (phase_x_frac - phase_y_frac);

        float temp_interp45Deg[7];
        temp_interp45Deg[1] = mix(p[2][1], p[1][2], pphase_b45);
        temp_interp45Deg[3] = mix(p[3][2], p[2][3], pphase_b45);
        temp_interp45Deg[5] = mix(p[4][3], p[3][4], pphase_b45);
        {
            pphase_b45 = pphase_b45 - 0.5;
            float a = (pphase_b45 >= 0.0) ? p[0][2] : p[2][0];
            float b = (pphase_b45 >= 0.0) ? p[1][3] : p[3][1];
            float c = (pphase_b45 >= 0.0) ? p[2][4] : p[4][2];
            float d = (pphase_b45 >= 0.0) ? p[3][5] : p[5][3];
            temp_interp45Deg[0] = mix(p[1][1], a, abs(pphase_b45));
            temp_interp45Deg[2] = mix(p[2][2], b, abs(pphase_b45));
            temp_interp45Deg[4] = mix(p[3][3], c, abs(pphase_b45));
            temp_interp45Deg[6] = mix(p[4][4], d, abs(pphase_b45));
        }

        float interp45Deg[6];
        float pphase_p45 = phase_x_frac + phase_y_frac;
        if (pphase_p45 >= 1.0) {
            for (int i = 0; i < 6; ++i) {
                interp45Deg[i] = temp_interp45Deg[i + 1];
            }
            pphase_p45 = pphase_p45 - 1.0;
        } else {
            for (int i = 0; i < 6; ++i) {
                interp45Deg[i] = temp_interp45Deg[i];
            }
        }

        f += evalPoly6(interp45Deg, int(pphase_p45 * 64.0)) * w.z;
    }
    if (w.w > 0.0) {
        // 135 deg filter
        float pphase_b135 = 0.5 * (phase_x_frac + phase_y_frac);

        float temp_interp135Deg[7];
        temp_interp135Deg[1] = mix(p[3][1], p[4][2], pphase_b135);
        temp_interp135Deg[3] = mix(p[2][2], p[3][3], pphase_b135);
        temp_interp135Deg[5] = mix(p[1][3], p[2][4], pphase_b135);
        {
            pphase_b135 = pphase_b135 - 0.5;
            float a = (pphase_b135 >= 0.0) ? p[5][2] : p[3][0];
            float b = (pphase_b135 >= 0.0) ? p[4][3] : p[2][1];
            float c = (pphase_b135 >= 0.0) ? p[3][4] : p[1][2];
            float d = (pphase_b135 >= 0.0) ? p[2][5] : p[0][3];
            temp_interp135Deg[0] = mix(p[4][1], a, abs(pphase_b135));
            temp_interp135Deg[2] = mix(p[3][2], b, abs(pphase_b135));
            temp_interp135Deg[4] = mix(p[2][3], c, abs(pphase_b135));
            temp_interp135Deg[6] = mix(p[1][4], d, abs(pphase_b135));
        }

        float interp135Deg[6];
        float pphase_p135 = 1.0 + (phase_x_frac - phase_y_frac);
        if (pphase_p135 >= 1.0) {
            for (int i = 0; i < 6; ++i) {
                interp135Deg[i] = temp_interp135Deg[i + 1];
            }
            pphase_p135 = pphase_p135 - 1.0;
        } else {
            for (int i = 0; i < 6; ++i) {
                interp135Deg[i] = temp_interp135Deg[i];
            }
        }

        f += evalPoly6(interp135Deg, int(pphase_p135 * 64.0)) * w.w;
    }
    return f;
}

vec3 applyDLS(vec3 center, vec2 uv, float sharp) {
    vec2 texel  = vec2(pc.invSrcW, pc.invSrcH);
    float SAT   = 1.0 + sharp * 0.20;
    float CON   = 1.0 + sharp * 0.12;
    float SHARP = sharp * 1.2;
    vec3 c = clamp((center - 0.5) * CON + 0.5, 0.0, 1.0);
    float gray = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(gray), c, SAT);

    vec3 blur = (texture(texSampler, uv + vec2( 0.0,    -texel.y)).rgb
               + texture(texSampler, uv + vec2( 0.0,     texel.y)).rgb
               + texture(texSampler, uv + vec2(-texel.x,  0.0   )).rgb
               + texture(texSampler, uv + vec2( texel.x,  0.0   )).rgb) * 0.25;
    return clamp(c + (center - blur) * SHARP, 0.0, 1.0);
}

vec3 applyCRT(vec3 center, vec2 uv) {

    const float CA = 1.0025;
    float r = texture(texSampler, (uv - 0.5) * CA + 0.5).r;
    float b = texture(texSampler, (uv - 0.5) / CA + 0.5).b;
    vec3 fc  = vec3(r, center.g, b);
    float sx = abs(sin(uv.x * 1024.0) * 0.5 * 0.125);
    float sy = abs(sin(uv.y * 1024.0) * 0.5 * 0.375);
    return mix(fc, vec3(0.0), sx + sy);
}

vec3 applyHDR(vec3 center, vec2 uv) {
    vec2 texel = vec2(pc.invSrcW, pc.invSrcH);
    const float r1 = 0.793, r2 = 0.870;
    vec3 b1 = vec3(0.0), b2 = vec3(0.0);
    vec2 offs[8] = vec2[](
        vec2( 1.5, -1.5), vec2(-1.5, -1.5), vec2( 1.5,  1.5), vec2(-1.5,  1.5),
        vec2( 0.0, -2.5), vec2( 0.0,  2.5), vec2(-2.5,  0.0), vec2( 2.5,  0.0)
    );
    for (int i = 0; i < 8; i++) {
        b1 += texture(texSampler, uv + offs[i] * r1 * texel).rgb;
        b2 += texture(texSampler, uv + offs[i] * r2 * texel).rgb;
    }
    b1 *= 0.005; b2 *= 0.010;
    vec3 hdr = (center + (b2 - b1)) * (r2 - r1);
    return clamp(pow(abs(hdr + center), vec3(1.30)) + hdr, 0.0, 1.0);
}

vec3 applyNatural(vec3 c) {
    mat3 toYIQ = mat3( 0.299,  0.596,  0.212,
                       0.587, -0.275, -0.523,
                       0.114, -0.321,  0.311);
    mat3 toRGB = mat3( 1.0,         1.0,         1.0,
                       0.95568806, -0.27158179, -1.10817732,
                       0.61985809, -0.64687381,  1.70506455);
    vec3 t = c * toYIQ;
    t = vec3(pow(t.r, 1.12), t.g * 1.2, t.b * 1.2);
    return clamp(t * toRGB, 0.0, 1.0);
}

void applyPostFX(inout vec3 rgb, vec2 uv) {
    if      (pc.effectId == 1) rgb = applyDLS    (rgb, uv, pc.sharpness);
    else if (pc.effectId == 2) rgb = applyCRT    (rgb, uv);
    else if (pc.effectId == 3) rgb = applyHDR    (rgb, uv);
    else if (pc.effectId == 4) rgb = applyNatural(rgb);
}

void main() {
    // Sharpness-derived params - mirror NVScalerUpdateConfig (SDR path).
    float sharpness = clamp(pc.sharpness, 0.0, 1.0);
    float sharpen_slider = sharpness - 0.5;
    float MaxScale   = (sharpen_slider >= 0.0) ? 1.25 : 1.75;
    float MinScale   = (sharpen_slider >= 0.0) ? 1.25 : 1.0;
    float LimitScale = (sharpen_slider >= 0.0) ? 1.25 : 1.0;

    gSharpStrengthMin   = max(0.0, 0.4 + sharpen_slider * MinScale * 1.2);
    float sharpStrengthMax = 1.6 + sharpen_slider * MaxScale * 1.8;
    gSharpStrengthScale = sharpStrengthMax - gSharpStrengthMin;
    gSharpLimitMin      = max(0.1, 0.14 + sharpen_slider * LimitScale * 0.32);
    float sharpLimitMax = 0.5 + sharpen_slider * LimitScale * 0.6;
    gSharpLimitScale    = sharpLimitMax - gSharpLimitMin;

    vec2 inSize = vec2(pc.srcW, pc.srcH);
    vec2 invIn  = vec2(pc.invSrcW, pc.invSrcH);

    // Source sample position in input-pixel space (output viewport cancels out).
    float srcX = fragTexCoord.x * inSize.x - 0.5;
    float srcY = fragTexCoord.y * inSize.y - 0.5;
    float fsx = floor(srcX);
    float fsy = floor(srcY);
    float fx = srcX - fsx;
    float fy = srcY - fsy;
    int fx_int = int(fx * float(kPhaseCount));
    int fy_int = int(fy * float(kPhaseCount));

    // 6x6 luma support: p[i][j] = luma at source texel (fsx + j - 2, fsy + i - 2).
    float p[6][6];
    for (int i = 0; i < 6; ++i) {
        for (int j = 0; j < 6; ++j) {
            vec2 tc = (vec2(fsx + float(j) - 2.0, fsy + float(i) - 2.0) + 0.5) * invIn;
            p[i][j] = getY(textureLod(texSampler, tc, 0.0).rgb);
        }
    }

    // 2x2 edge map around the source pixel (3x3 luma window per corner).
    vec4 e00 = edgeAt(p[1][1], p[1][2], p[1][3],  p[2][1], p[2][2], p[2][3],  p[3][1], p[3][2], p[3][3]);
    vec4 e01 = edgeAt(p[1][2], p[1][3], p[1][4],  p[2][2], p[2][3], p[2][4],  p[3][2], p[3][3], p[3][4]);
    vec4 e10 = edgeAt(p[2][1], p[2][2], p[2][3],  p[3][1], p[3][2], p[3][3],  p[4][1], p[4][2], p[4][3]);
    vec4 e11 = edgeAt(p[2][2], p[2][3], p[2][4],  p[3][2], p[3][3], p[3][4],  p[4][2], p[4][3], p[4][4]);
    vec4 w = getInterpEdgeMap(e00, e01, e10, e11, fx, fy); // * NIS_SCALE_INT (==1)

    float baseWeight = 1.0 - w.x - w.y - w.z - w.w; // NIS_SCALE_FLOAT - sum
    float opY = filterNormal(p, fx_int, fy_int) * baseWeight;
    opY += addDirFilters(p, fx, fy, fx_int, fy_int, w);

    // Bilinear color tap (coord reduces to fragTexCoord) + luma correction.
    vec4 op = texture(texSampler, fragTexCoord);
    float y = getY(op.rgb);
    float corr = opY - y; // * (1/NIS_SCALE_FLOAT) == 1
    op.rgb += vec3(corr);

    vec3 rgb = clamp(op.rgb, 0.0, 1.0);
    if (pc.effectId != 0) applyPostFX(rgb, fragTexCoord);
    outColor = vec4(rgb, (pc.useTexAlpha != 0) ? op.a : 1.0);
}
