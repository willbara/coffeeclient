package dev.coffeeclient.gui.anim;

/** Small helpers for choreographing motion: sub-ranges, waves and stable noise. */
public final class Motion {
	private static final long START = System.nanoTime();

	private Motion() {
	}

	/** Seconds since the client started, for looping effects. */
	public static float time() {
		return (System.nanoTime() - START) / 1e9f;
	}

	/** Where {@code t} falls inside {@code [a, b]}, clamped to 0..1. Used to stagger parts of one run. */
	public static float range(float t, float a, float b) {
		if (b <= a) return t >= b ? 1f : 0f;
		return Math.max(0f, Math.min(1f, (t - a) / (b - a)));
	}

	/** 0..1 sine wave with the given period in seconds. */
	public static float wave(float time, float period, float phase) {
		return 0.5f + 0.5f * (float) Math.sin((time / period + phase) * Math.PI * 2);
	}

	/** A sharp beat that peaks once per period (heartbeat-like). */
	public static float pulse(float time, float period) {
		float p = (time / period) % 1f;
		return (float) Math.pow(1 - p, 4);
	}

	/** Deterministic hash of an int to 0..1. */
	public static float hash(int n) {
		n = (n << 13) ^ n;
		n = n * (n * n * 15731 + 789221) + 1376312589;
		return (n & 0x7fffffff) / (float) 0x7fffffff;
	}

	public static float hash(int a, int b) {
		return hash(a * 73856093 ^ b * 19349663);
	}

	public static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	public static float clamp01(float v) {
		return Math.max(0f, Math.min(1f, v));
	}
}
