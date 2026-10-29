#version 330

#moj_import <minecraft:globals.glsl>

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform FilterConfig {
    float Strength;
};

out vec4 fragColor;

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

// GameTime is the day fraction; this turns it into seconds
float seconds() {
    return GameTime * 1200.0;
}

// Pen and ink: Sobel edges as black lines, three flat tones, cross-hatching in the shadows.
float lumaAt(vec2 uv) {
    return luma(texture(InSampler, uv).rgb);
}

void main() {
    vec2 px = 1.0 / InSize;
    float tl = lumaAt(texCoord + px * vec2(-1.0, -1.0)), t = lumaAt(texCoord + px * vec2(0.0, -1.0)), tr = lumaAt(texCoord + px * vec2(1.0, -1.0));
    float l = lumaAt(texCoord + px * vec2(-1.0, 0.0)), r = lumaAt(texCoord + px * vec2(1.0, 0.0));
    float bl = lumaAt(texCoord + px * vec2(-1.0, 1.0)), b = lumaAt(texCoord + px * vec2(0.0, 1.0)), br = lumaAt(texCoord + px * vec2(1.0, 1.0));
    float gx = -tl - 2.0 * l - bl + tr + 2.0 * r + br;
    float gy = -tl - 2.0 * t - tr + bl + 2.0 * b + br;
    float edge = smoothstep(0.25, 0.6, length(vec2(gx, gy)));
    vec3 c = texture(InSampler, texCoord).rgb;
    float v = luma(c);
    float tone = v > 0.6 ? 1.0 : v > 0.3 ? 0.78 : 0.55;
    vec2 p = texCoord * OutSize;
    float hatch = 1.0;
    if (v < 0.45 && mod(p.x + p.y, 6.0) < 1.0) hatch = 0.35;
    if (v < 0.22 && mod(p.x - p.y, 6.0) < 1.0) hatch = 0.2;
    vec3 paper = vec3(0.96, 0.93, 0.86) * tone * hatch;
    vec3 outc = mix(paper, vec3(0.08, 0.06, 0.05), edge);
    fragColor = vec4(mix(c, outc, Strength), 1.0);
}
