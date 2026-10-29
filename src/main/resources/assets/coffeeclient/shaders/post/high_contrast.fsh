#version 330

#moj_import <minecraft:globals.glsl>

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform FilterConfig {
    float Contrast;
    float Levels;
    float Edges;
    float Invert;
};

out vec4 fragColor;

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

float lumaAt(vec2 uv) {
    return luma(texture(InSampler, uv).rgb);
}

// High contrast: a contrast curve, then posterise to Levels steps (1 = black and white),
// dark Sobel edges, and an optional invert.
void main() {
    vec3 c = texture(InSampler, texCoord).rgb;
    c = clamp((c - 0.5) * Contrast + 0.5, 0.0, 1.0);
    if (Levels <= 1.0) {
        c = vec3(step(0.5, luma(c)));
    } else if (Levels < 64.0) {
        c = floor(c * Levels + 0.5) / Levels;
    }
    if (Edges > 0.0) {
        vec2 px = 1.0 / InSize;
        float tl = lumaAt(texCoord + px * vec2(-1.0, -1.0)), t = lumaAt(texCoord + px * vec2(0.0, -1.0)), tr = lumaAt(texCoord + px * vec2(1.0, -1.0));
        float l = lumaAt(texCoord + px * vec2(-1.0, 0.0)), r = lumaAt(texCoord + px * vec2(1.0, 0.0));
        float bl = lumaAt(texCoord + px * vec2(-1.0, 1.0)), b = lumaAt(texCoord + px * vec2(0.0, 1.0)), br = lumaAt(texCoord + px * vec2(1.0, 1.0));
        float gx = -tl - 2.0 * l - bl + tr + 2.0 * r + br;
        float gy = -tl - 2.0 * t - tr + bl + 2.0 * b + br;
        c = mix(c, vec3(0.0), smoothstep(0.2, 0.5, length(vec2(gx, gy))) * Edges);
    }
    c = mix(c, 1.0 - c, Invert);
    fragColor = vec4(c, 1.0);
}
