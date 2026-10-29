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

// Retro palette: each channel snapped to a few levels through a 4x4 Bayer pattern on 2px cells.
const float BAYER[16] = float[](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);

void main() {
    vec2 cell = floor(texCoord * OutSize / 2.0);
    vec2 snapped = (cell * 2.0 + 1.0) / OutSize;
    vec3 c = texture(InSampler, snapped).rgb;
    int i = int(mod(cell.x, 4.0)) + int(mod(cell.y, 4.0)) * 4;
    float threshold = (BAYER[i] + 0.5) / 16.0 - 0.5;
    float levels = 4.0;
    vec3 q = floor(c * (levels - 1.0) + 0.5 + threshold) / (levels - 1.0);
    fragColor = vec4(mix(texture(InSampler, texCoord).rgb, clamp(q, 0.0, 1.0), Strength), 1.0);
}
