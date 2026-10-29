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

// Lens fringing: red and blue pulled apart toward the edges.
void main() {
    vec2 d = texCoord - 0.5;
    vec2 off = d * dot(d, d) * 0.06 * Strength + d * 0.004 * Strength;
    float rch = texture(InSampler, texCoord + off).r;
    float gch = texture(InSampler, texCoord).g;
    float bch = texture(InSampler, texCoord - off).b;
    vec3 c = vec3(rch, gch, bch) * (1.0 - dot(d, d) * 0.5 * Strength);
    fragColor = vec4(c, 1.0);
}
