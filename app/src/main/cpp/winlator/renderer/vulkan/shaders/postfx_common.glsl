// PostFX effects 1..4 (DLS, CRT, HDR, Natural), shared by every shader that applies the graphics
// panel's effect: the single-pass upscalers (SGSR, SGSR HQ, NIS, FSR, Lanczos) run it on their
// output, window_postfx on the plain blit and on top of ColorBoost. Each function takes the
// colour to work on; the neighbourhood taps read the source texture. Include after texSampler is
// declared (needs GL_GOOGLE_include_directive). texel = one source pixel in UV.
//
// doSharpen: DLS's unsharp mask is applied only when nothing else in the pass already sharpens
// (the plain blit). Under an upscaler or ColorBoost, which sharpen with the same slider, it is
// false and DLS only does its contrast / saturation lift - otherwise the slider acts twice.

vec3 applyDLS(vec3 center, vec2 uv, vec2 texel, float sharp, bool doSharpen) {
    float SAT   = 1.0 + sharp * 0.20;
    float CON   = 1.0 + sharp * 0.12;
    vec3 c = clamp((center - 0.5) * CON + 0.5, 0.0, 1.0);
    float gray = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(gray), c, SAT);
    if (!doSharpen) return c;

    float SHARP = sharp * 1.2;
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

vec3 applyHDR(vec3 center, vec2 uv, vec2 texel) {
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
    t = vec3(pow(max(t.r, 0.0), 1.12), t.g * 1.2, t.b * 1.2);
    return clamp(t * toRGB, 0.0, 1.0);
}

// Any other id (0, or a window_postfx-only effect) leaves rgb as it is.
vec3 applyPostFXEffect(int id, vec3 rgb, vec2 uv, vec2 texel, float sharp, bool doSharpen) {
    if      (id == 1) return applyDLS    (rgb, uv, texel, sharp, doSharpen);
    else if (id == 2) return applyCRT    (rgb, uv);
    else if (id == 3) return applyHDR    (rgb, uv, texel);
    else if (id == 4) return applyNatural(rgb);
    return rgb;
}
