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

// Coffee colour grade: warm sepia tones, gentle contrast and soft dark edges.
void main() {
    vec3 c = texture(InSampler, texCoord).rgb;
    float l = luma(c);
    vec3 sepia = vec3(l * 1.07 + 0.04, l * 0.86 + 0.02, l * 0.62);
    vec3 graded = mix(c, sepia, 0.55);
    graded = mix(graded, graded * graded * (3.0 - 2.0 * graded), 0.35);
    vec2 d = texCoord - 0.5;
    graded *= mix(1.0, 1.0 - dot(d, d) * 1.2, 0.8);
    fragColor = vec4(mix(c, graded, Strength), 1.0);
}
