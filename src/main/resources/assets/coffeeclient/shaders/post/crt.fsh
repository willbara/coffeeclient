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

// An old tube: curved glass, beam bloom, scanlines, a slow rolling refresh band and dark corners.
void main() {
    vec2 uv = texCoord - 0.5;
    uv *= 1.0 + dot(uv, uv) * 0.18 * Strength;
    uv += 0.5;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec2 px = 1.0 / InSize;
    vec3 c = texture(InSampler, uv).rgb * 0.6
        + texture(InSampler, uv + vec2(px.x * 1.5, 0.0)).rgb * 0.2
        + texture(InSampler, uv - vec2(px.x * 1.5, 0.0)).rgb * 0.2;
    float scan = 0.82 + 0.18 * cos(uv.y * OutSize.y * 3.14159);
    float roll = fract(uv.y - seconds() * 0.12);
    float band = exp(-pow((roll - 0.5) * 14.0, 2.0)) * 0.06;
    vec2 d = abs(uv - 0.5) * 2.0;
    float edge = smoothstep(1.0, 0.82, max(d.x * d.x, d.y * d.y) * 0.5 + max(d.x, d.y) * 0.5);
    float flicker = 1.0 - 0.015 * sin(seconds() * 60.0);
    vec3 tube = (c * scan + band) * edge * flicker;
    fragColor = vec4(mix(texture(InSampler, texCoord).rgb, tube, Strength), 1.0);
}
