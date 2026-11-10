// SPDX-FileCopyrightText: Copyright 2026 qwertypower (DEVAR Entertainment LLC)
// SPDX-License-Identifier: MIT
//
// WFG frame generation: one triangle that covers the whole target, for dis_interpolate.frag.

#version 450

void main() {
    // (-1,-1), (3,-1), (-1,3): the viewport is inside it, no vertex input needed.
    vec2 p = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
