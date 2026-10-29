package dev.coffeeclient.util;

/** ARGB colour math. All colours in the client are 0xAARRGGBB ints. */
public final class Colors {
	private Colors() {
	}

	public static int argb(int a, int r, int g, int b) {
		return (clamp(a) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
	}

	public static int rgb(int r, int g, int b) {
		return argb(255, r, g, b);
	}

	public static int alpha(int c) {
		return c >>> 24;
	}

	public static int red(int c) {
		return (c >> 16) & 0xFF;
	}

	public static int green(int c) {
		return (c >> 8) & 0xFF;
	}

	public static int blue(int c) {
		return c & 0xFF;
	}

	public static int withAlpha(int c, int alpha) {
		return (clamp(alpha) << 24) | (c & 0xFFFFFF);
	}

	/** Multiply the existing alpha by {@code factor}. */
	public static int fade(int c, float factor) {
		return withAlpha(c, Math.round(alpha(c) * Math.max(0, Math.min(1, factor))));
	}

	public static int lerp(int a, int b, float t) {
		t = Math.max(0, Math.min(1, t));
		return argb(
			Math.round(alpha(a) + (alpha(b) - alpha(a)) * t),
			Math.round(red(a) + (red(b) - red(a)) * t),
			Math.round(green(a) + (green(b) - green(a)) * t),
			Math.round(blue(a) + (blue(b) - blue(a)) * t));
	}

	/** Scale RGB brightness, keeping alpha. */
	public static int shade(int c, float factor) {
		return argb(alpha(c), Math.round(red(c) * factor), Math.round(green(c) * factor), Math.round(blue(c) * factor));
	}

	/** @return {h in [0,360), s in [0,1], v in [0,1]} */
	public static float[] toHsv(int c) {
		float r = red(c) / 255f, g = green(c) / 255f, b = blue(c) / 255f;
		float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
		float d = max - min;
		float h;
		if (d == 0) h = 0;
		else if (max == r) h = 60 * (((g - b) / d) % 6);
		else if (max == g) h = 60 * ((b - r) / d + 2);
		else h = 60 * ((r - g) / d + 4);
		if (h < 0) h += 360;
		return new float[] {h, max == 0 ? 0 : d / max, max};
	}

	public static int fromHsv(float h, float s, float v, int alpha) {
		s = Math.max(0, Math.min(1, s));
		v = Math.max(0, Math.min(1, v));
		float c = v * s;
		float x = c * (1 - Math.abs((h / 60f) % 2 - 1));
		float m = v - c;
		float r, g, b;
		int sector = (int) (h / 60f) % 6;
		switch (sector) {
			case 0 -> { r = c; g = x; b = 0; }
			case 1 -> { r = x; g = c; b = 0; }
			case 2 -> { r = 0; g = c; b = x; }
			case 3 -> { r = 0; g = x; b = c; }
			case 4 -> { r = x; g = 0; b = c; }
			default -> { r = c; g = 0; b = x; }
		}
		return argb(alpha, Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255));
	}

	private static int clamp(int v) {
		return Math.max(0, Math.min(255, v));
	}
}
