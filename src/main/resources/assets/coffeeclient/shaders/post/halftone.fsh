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

// Newsprint: a rotated grid of ink dots sized by darkness, on warm paper.
void main() {
    float size = 5.0;
    vec2 p = texCoord * OutSize;
    mat2 rot = mat2(0.7071, -0.7071, 0.7071, 0.7071);
    vec2 r = rot * p;
    vec2 cellCenter = (floor(r / size) + 0.5) * size;
    vec2 sampleAt = transpose(rot) * cellCenter / OutSize;
    vec3 c = texture(InSampler, sampleAt).rgb;
    float dark = 1.0 - luma(c);
    float radius = sqrt(dark) * size * 0.62;
    float dist = length(r - cellCenter);
    float ink = 1.0 - smoothstep(radius - 0.6, radius + 0.6, dist);
    vec3 paper = vec3(0.95, 0.91, 0.82);
    vec3 inkColor = mix(vec3(0.12, 0.08, 0.06), c * 0.6, 0.35);
    vec3 outc = mix(paper, inkColor, ink);
    fragColor = vec4(mix(texture(InSampler, texCoord).rgb, outc, Strength), 1.0);
}
