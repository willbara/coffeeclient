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

// Cinematic darkened corners with a hint of warmth.
void main() {
    vec3 c = texture(InSampler, texCoord).rgb;
    vec2 d = (texCoord - 0.5) * vec2(OutSize.x / OutSize.y, 1.0);
    float v = smoothstep(0.95, 0.25, length(d));
    vec3 outc = c * mix(1.0, v, 0.85) + vec3(0.02, 0.01, 0.0) * (1.0 - v);
    fragColor = vec4(mix(c, outc, Strength), 1.0);
}
