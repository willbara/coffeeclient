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

// Black and white with crushed contrast, film grain and a heavy vignette.
void main() {
    vec3 c = texture(InSampler, texCoord).rgb;
    float l = luma(c);
    l = smoothstep(0.08, 0.92, l);
    l = l * l * (3.0 - 2.0 * l);
    float grain = hash(floor(texCoord * OutSize) + floor(seconds() * 24.0)) - 0.5;
    l += grain * 0.09;
    vec2 d = texCoord - 0.5;
    l *= 1.0 - dot(d, d) * 1.6;
    fragColor = vec4(mix(c, vec3(l), Strength), 1.0);
}
