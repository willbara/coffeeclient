package dev.coffeeclient.gui.fx;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.anim.Motion;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.util.Colors;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Animated scenes drawn behind client screens. Every particle's position is a pure function of time and
 * its index (no simulation state), so scenes cost nothing while hidden and never drift or pile up.
 * Colours come from the theme accent, so the same scene changes mood with the preset.
 */
public final class Backdrop {
	public enum Scene { NONE, STEAM, BEANS, SNOW, PETALS, BUBBLES, EMBERS, RAIN, GRID, AURORA, FOG, SILK }

	private Backdrop() {
	}

	/**
	 * @param alpha overall visibility 0..1
	 * @param mx    mouse x, for parallax (pass w/2 for none)
	 */
	public static void draw(GuiGraphicsExtractor g, Theme t, Scene scene, int w, int h, float alpha, int mx, int my) {
		if (scene == Scene.NONE || alpha <= 0.01f) return;
		float time = Motion.time();
		float px = (mx - w / 2f) / Math.max(1, w), py = (my - h / 2f) / Math.max(1, h);
		switch (scene) {
			case STEAM -> steam(g, t, w, h, time, alpha, px);
			case BEANS -> beans(g, t, w, h, time, alpha, px, py);
			case SNOW -> snow(g, t, w, h, time, alpha, px);
			case PETALS -> petals(g, t, w, h, time, alpha, px);
			case BUBBLES -> bubbles(g, t, w, h, time, alpha, px);
			case EMBERS -> embers(g, t, w, h, time, alpha, px);
			case RAIN -> rain(g, t, w, h, time, alpha);
			case GRID -> grid(g, t, w, h, time, alpha, px);
			case AURORA -> aurora(g, t, w, h, time, alpha);
			case FOG -> fog(g, t, w, h, time, alpha);
			case SILK -> silk(g, t, w, h, time, alpha, px, py);
			case NONE -> {
			}
		}
	}

	private static float fract(float v) {
		return v - (float) Math.floor(v);
	}

	private static int a(int color, float alpha) {
		return Colors.withAlpha(color, Math.round(255 * Motion.clamp01(alpha)));
	}

	// ---- scenes ----

	/**
	 * Steam: soft puffs that lift off the bottom edge, rise slowly, swell and thin out until they vanish.
	 * Each puff drifts only a little sideways, and its own way, so the column breathes rather than sways.
	 */
	private static void steam(GuiGraphicsExtractor g, Theme t, int w, int h, float time, float alpha, float px) {
		int color = Colors.lerp(t.text, t.accent, 0.3f);
		int count = Math.max(24, w / 14);
		for (int i = 0; i < count; i++) {
			float life = 7f + Motion.hash(i, 3) * 5f;
			float f = fract(time / life + Motion.hash(i, 5));
			// rises fast at first, then slows as it cools
			float rise = 1 - (1 - f) * (1 - f);
			float y = h + 12 - rise * h * (0.55f + Motion.hash(i, 7) * 0.35f);
			float drift = (Motion.hash(i, 9) - 0.5f) * 50 * f + (float) Math.sin(f * 2.5f + i) * 3 * f;
			float x = Motion.hash(i) * w + drift + px * 18 * (0.5f + f);
			float r = 4 + f * 18;
			// fades in quickly off the edge, then thins away
			float fade = Motion.clamp01(f / 0.12f) * (1 - f) * (1 - f);
			puff(g, x, y, r, color, fade * 0.28f * alpha);
		}
	}

	/** A soft disc: a faint wide circle with a slightly denser core, drawn as 2px rows. */
	private static void puff(GuiGraphicsExtractor g, float cx, float cy, float r, int color, float alpha) {
		if (alpha <= 0.004f) return;
		disc(g, cx, cy, r, a(color, alpha * 0.55f));
		disc(g, cx, cy - r * 0.15f, r * 0.6f, a(color, alpha * 0.6f));
	}

	private static void disc(GuiGraphicsExtractor g, float cx, float cy, float r, int color) {
		int ir = Math.max(1, Math.round(r));
		for (int dy = -ir; dy < ir; dy += 2) {
			float yy = (dy + 1) / r;
			int half = Math.round(r * (float) Math.sqrt(Math.max(0, 1 - yy * yy)));
			if (half > 0) Draw.rect(g, Math.round(cx) - half, Math.round(cy) + dy, half * 2, 2, color);
		}
	}

	/** Coffee beans drifting upward and turning. */
	private static void beans(GuiGraphicsExtractor g, Theme t, int w, int h, float time, float alpha, float px, float py) {
		int bean = Colors.lerp(0xFF3A2416, Colors.shade(t.accent, 0.45f), 0.3f);
		int crease = Colors.shade(bean, 0.45f);
		int count = Math.max(10, w * h / 22000);
		for (int i = 0; i < count; i++) {
			float depth = 0.4f + Motion.hash(i, 1) * 0.6f;
			float x = Motion.hash(i) * w + (float) Math.sin(time * 0.3f + i) * 12 + px * 40 * depth;
			float y = h + 20 - fract(Motion.hash(i, 2) + time * 0.012f * (0.5f + depth)) * (h + 40) + py * 20 * depth;
			float size = 4 + depth * 6;
			float rot = time * (0.2f + Motion.hash(i, 4) * 0.4f) * (i % 2 == 0 ? 1 : -1) + i;
			g.pose().pushMatrix();
			g.pose().translate(x, y);
			g.pose().rotate(rot);
			int rows = Math.round(size * 2);
			for (int r = 0; r < rows; r++) {
				float ry = (r + 0.5f) / rows * 2 - 1;
				int half = Math.round(size * 0.7f * (float) Math.sqrt(Math.max(0, 1 - ry * ry)));
				Draw.rect(g, -half, r - rows / 2, half * 2, 1, a(bean, alpha * 0.55f * depth));
			}
			Draw.rect(g, -1, -rows / 2 + 2, 1, rows - 4, a(crease, alpha * 0.6f * depth));
			g.pose().popMatrix();
		}
	}

	/** Three layers of falling snow. */
	private static void snow(GuiGraphicsExtractor g, Theme t, int w, int h, float time, float alpha, float px) {
		int count = Math.min(240, w * h / 2500);
		for (int i = 0; i < count; i++) {
			float depth = (i % 3 + 1) / 3f;
			float y = fract(Motion.hash(i, 1) + time * 0.03f * (0.4f + depth)) * (h + 10) - 5;
			float x = fract(Motion.hash(i) + (float) Math.sin(time * 0.5f + i) * 0.01f) * w + px * 30 * depth;
			int s = depth > 0.9f ? 2 : 1;
			Draw.rect(g, Math.round(x), Math.round(y), s, s, a(0xFFFFFFFF, alpha * (0.25f + depth * 0.55f)));
		}
	}

	/** Petals (or leaves, with a green accent) tumbling down in a breeze. */
	private static void petals(GuiGraphicsExtractor g, Theme t, int w, int h, float time, float alpha, float px) {
		int count = Math.max(18, w * h / 14000);
		float gust = (float) Math.sin(time * 0.25f) * 0.5f + 0.5f;
		for (int i = 0; i < count; i++) {
			float depth = 0.5f + Motion.hash(i, 3) * 0.5f;
			float f = fract(Motion.hash(i, 1) + time * 0.025f * depth);
			float y = f * (h + 30) - 15;
			float x = fract(Motion.hash(i) + f * (0.15f + gust * 0.1f)) * (w + 40) - 20 + px * 30 * depth;
			float spin = (float) Math.cos(time * (1 + Motion.hash(i, 4) * 2) + i);
			int pw = Math.max(1, Math.round(Math.abs(spin) * 4 * depth)), ph = Math.round(3 * depth) + 1;
			int color = Colors.lerp(t.accent, t.accent2, Motion.hash(i, 6));
			Draw.rect(g, Math.round(x), Math.round(y), pw, ph, a(color, alpha * 0.7f * depth));
			Draw.rect(g, Math.round(x), Math.round(y), Math.max(1, pw / 2), 1, a(0xFFFFFFFF, alpha * 0.25f * depth));
		}
	}

	/** Glassy bubbles rising with a wobble, plus slanted light beams. */
	private static void bubbles(GuiGraphicsExtractor g, Theme t, int w, int h, float time, float alpha, float px) {
		for (int b = 0; b < 4; b++) {
			float bx = (b + 0.5f) / 4 * w + (float) Math.sin(time * 0.1f + b) * 40;
			Draw.quad(g, bx, 0, bx + 50, 0, bx - 60, h, bx - 110, h, a(0xFFFFFFFF, alpha * 0.035f));
		}
		int count = Math.max(14, w * h / 16000);
		for (int i = 0; i < count; i++) {
			float depth = 0.4f + Motion.hash(i, 1) * 0.6f;
			float r = 3 + depth * 9;
			float y = h + r - fract(Motion.hash(i, 2) + time * 0.03f * depth) * (h + r * 2);
			float x = Motion.hash(i) * w + (float) Math.sin(time * 1.3f + i * 2.1f) * 6 + px * 30 * depth;
			ring(g, x, y, r, a(Colors.lerp(t.accent, 0xFFFFFFFF, 0.4f), alpha * 0.45f * depth));
			Draw.rect(g, Math.round(x - r * 0.45f), Math.round(y - r * 0.5f), 2, 2, a(0xFFFFFFFF, alpha * 0.7f * depth));
		}
	}

	private static void ring(GuiGraphicsExtractor g, float cx, float cy, float r, int color) {
		int seg = 12;
		for (int s = 0; s < seg; s++) {
			double a0 = s * Math.PI * 2 / seg, a1 = (s + 1) * Math.PI * 2 / seg;
			Draw.stroke(g, cx + (float) Math.cos(a0) * r, cy + (float) Math.sin(a0) * r,
				cx + (float) Math.cos(a1) * r, cy + (float) Math.sin(a1) * r, 1, color);
		}
	}

	/** Sparks rising from below, flickering from accent 2 to the accent as they cool. */
	private static void embers(GuiGraphicsExtractor g, Theme t, int w, int h, float time, float alpha, float px) {
		int count = Math.min(220, w * h / 3500);
		for (int i = 0; i < count; i++) {
			float depth = 0.4f + Motion.hash(i, 1) * 0.6f;
			float f = fract(Motion.hash(i, 2) + time * 0.05f * depth);
			float y = h - f * h * 0.9f;
			float x = Motion.hash(i) * w + (float) Math.sin(f * 9 + i) * 14 * f + px * 25 * depth;
			float flicker = 0.6f + 0.4f * (float) Math.sin(time * 14 + i * 3);
			float life = (1 - f) * flicker * depth;
			int color = Colors.lerp(t.accent2, t.accent, f);
			int s = depth > 0.75f ? 2 : 1;
			Draw.rect(g, Math.round(x) - 1, Math.round(y) - 1, s + 2, s + 2, a(color, alpha * life * 0.18f));
			Draw.rect(g, Math.round(x), Math.round(y), s, s, a(Colors.lerp(color, 0xFFFFFFFF, 0.3f), alpha * life));
		}
	}

	/** Digital rain: columns of falling cells with bright heads. */
	private static void rain(GuiGraphicsExtractor g, Theme t, int w, int h, float time, float alpha) {
		int cell = 6;
		int cols = w / cell + 1;
		int trail = 14;
		for (int c = 0; c < cols; c++) {
			float speed = 0.25f + Motion.hash(c, 1) * 0.5f;
			float head = fract(Motion.hash(c) + time * speed * 0.35f) * (h / (float) cell + trail * 1.5f);
			for (int k = 0; k < trail; k++) {
				int row = (int) head - k;
				if (row < 0 || row * cell > h) continue;
				float fade = 1 - k / (float) trail;
				boolean on = Motion.hash(c * 131 + row, (int) (time * 6) + k) > 0.25f;
				if (!on) continue;
				int color = k == 0 ? Colors.lerp(t.accent, 0xFFFFFFFF, 0.6f) : t.accent;
				Draw.rect(g, c * cell + 1, row * cell + 1, cell - 3, cell - 2, a(color, alpha * fade * (k == 0 ? 0.8f : 0.35f)));
			}
		}
	}

	/** Synthwave: a striped sun on the horizon over a scrolling perspective grid. */
	private static void grid(GuiGraphicsExtractor g, Theme t, int w, int h, float time, float alpha, float px) {
		int horizon = Math.round(h * 0.58f);
		float sunR = Math.min(w, h) * 0.2f;
		float sx = w / 2f + px * 20, sy = horizon - sunR * 0.35f;
		for (int r = (int) -sunR; r < 0; r++) {
			float ry = r / sunR;
			// horizontal gaps that widen toward the bottom of the sun
			if (r > -sunR * 0.55f && ((int) (r + time * 6) % 7 + 7) % 7 < 2 + (int) ((ry + 0.55f) * -8)) continue;
			float half = sunR * (float) Math.sqrt(1 - ry * ry);
			int color = Colors.lerp(t.accent2, t.accent, -ry);
			Draw.rect(g, Math.round(sx - half), Math.round(sy + r), Math.round(half * 2), 1, a(color, alpha * 0.55f));
		}
		Draw.gradientV(g, 0, horizon, w, h - horizon, a(t.accent, alpha * 0.08f), a(0xFF000000, alpha * 0.3f));
		int line = a(t.accent, alpha * 0.45f);
		float vx = w / 2f + px * 40;
		for (int i = -12; i <= 12; i++) {
			Draw.stroke(g, vx + i * 8, horizon, vx + i * w * 0.18f, h, 1, line);
		}
		for (int i = 0; i < 12; i++) {
			float z = fract(i / 12f + time * 0.15f);
			float y = horizon + (h - horizon) * z * z;
			Draw.rect(g, 0, Math.round(y), w, 1, a(t.accent, alpha * 0.45f * z));
		}
		Draw.rect(g, 0, horizon, w, 1, a(Colors.lerp(t.accent, 0xFFFFFFFF, 0.4f), alpha * 0.7f));
	}

	/** Curtains of light swaying across the top, with a few twinkling stars. */
	private static void aurora(GuiGraphicsExtractor g, Theme t, int w, int h, float time, float alpha) {
		for (int i = 0; i < 70; i++) {
			float tw = Motion.wave(time, 1.5f + Motion.hash(i, 1) * 3, Motion.hash(i, 2));
			Draw.rect(g, Math.round(Motion.hash(i) * w), Math.round(Motion.hash(i, 3) * h * 0.7f), 1, 1, a(0xFFFFFFFF, alpha * tw * 0.6f));
		}
		int step = 3;
		for (int layer = 0; layer < 2; layer++) {
			int color = layer == 0 ? t.accent : t.accent2;
			float base = h * (0.18f + layer * 0.12f);
			for (int x = 0; x < w; x += step) {
				float fx = x / (float) w;
				float wave = (float) (Math.sin(fx * 6 + time * 0.4f + layer * 2) * 0.5 + Math.sin(fx * 13 - time * 0.7f) * 0.25);
				float top = base + wave * h * 0.08f;
				float len = h * (0.12f + 0.1f * (float) Math.sin(fx * 9 + time * 0.9f + layer)) + 20;
				float bright = 0.5f + 0.5f * (float) Math.sin(fx * 20 + time * 1.2f + layer * 3);
				Draw.gradientV(g, x, Math.round(top), step, Math.round(len), a(color, alpha * (0.10f + bright * 0.16f)), a(color, 0));
			}
		}
	}

	/** Slow banks of fog drifting sideways. */
	private static void fog(GuiGraphicsExtractor g, Theme t, int w, int h, float time, float alpha) {
		int color = Colors.lerp(t.text, t.accent, 0.2f);
		for (int i = 0; i < 9; i++) {
			float y = h * (0.15f + i * 0.1f) + (float) Math.sin(time * 0.2f + i) * 10;
			float x = fract(Motion.hash(i) + time * 0.01f * (i % 2 == 0 ? 1 : -1)) * (w + 400) - 300;
			int bw = 260 + (int) (Motion.hash(i, 1) * 200), bh = 24 + (int) (Motion.hash(i, 2) * 30);
			for (int k = 0; k < 4; k++) {
				int inset = k * bh / 8;
				Draw.rect(g, Math.round(x) + inset * 3, Math.round(y) + inset, bw - inset * 6, bh - inset * 2, a(color, alpha * 0.035f));
			}
		}
	}

	/**
	 * Dark silk: a few broad ribbons of light folding slowly across black, like liquid chrome. Each ribbon is
	 * a soft band with a thin specular crest, and a highlight that wanders along it.
	 */
	private static void silk(GuiGraphicsExtractor g, Theme t, int w, int h, float time, float alpha, float px, float py) {
		int step = 4;
		int sheen = Colors.lerp(0xFFFFFFFF, t.accent, 0.1f);
		for (int i = 0; i < 3; i++) {
			float base = h * (0.28f + i * 0.24f) + py * 24 * (i + 1);
			float speed = 0.07f + i * 0.025f;
			float tilt = (i % 2 == 0 ? 0.22f : -0.18f) * h;
			for (int x = 0; x < w; x += step) {
				float fx = x / (float) w + px * 0.04f * (i + 1);
				float fold = (float) (Math.sin(fx * 3.1f + time * speed * 6 + i * 1.7f) * 0.6 + Math.sin(fx * 7.3f - time * speed * 4 + i) * 0.25);
				float cy = base + (fx - 0.5f) * tilt + fold * h * 0.09f;
				float thick = h * (0.05f + 0.035f * (float) Math.sin(fx * 5.2f + time * speed * 3 + i * 2));
				// light pools where the fabric turns toward the viewer, and drifts along the ribbon
				float light = 0.35f + 0.65f * Motion.wave(fx * 14 + time * speed * 2, 7f, i * 0.3f) * (0.5f + 0.5f * fold);
				int top = Math.round(cy - thick), mid = Math.round(cy), bottom = Math.round(cy + thick * 1.6f);
				int core = a(sheen, alpha * (0.06f + 0.13f * light));
				Draw.gradientV(g, x, top, step, mid - top, a(sheen, 0), core);
				Draw.gradientV(g, x, mid, step, bottom - mid, core, a(sheen, 0));
				Draw.rect(g, x, Math.round(cy - thick * 0.35f), step, 1, a(sheen, alpha * 0.28f * light * light));
			}
		}
	}
}
