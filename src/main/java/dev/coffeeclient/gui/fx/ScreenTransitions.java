package dev.coffeeclient.gui.fx;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.anim.Ease;
import dev.coffeeclient.gui.anim.Motion;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.util.Colors;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Open/close transitions for client screens. Each mode wraps the screen's own drawing with pose
 * transforms, scissors and the global alpha, so screens need no changes to take part.
 */
public final class ScreenTransitions {
	private ScreenTransitions() {
	}

	/**
	 * @param p       0..1 progress (runs backwards while closing)
	 * @param ease    the theme's motion curve, used while opening
	 * @param content draws the screen
	 */
	public static void draw(Ui ui, Theme.Transition mode, float p, boolean closing, Ease ease, Runnable content) {
		if (p >= 1f || mode == Theme.Transition.NONE) {
			content.run();
			return;
		}
		// closing reverses smoothly instead of replaying an overshoot backwards
		float e = closing ? Ease.IN_OUT_CUBIC.apply(p) : ease.apply(p);
		GuiGraphicsExtractor g = ui.g;
		int w = ui.screenWidth, h = ui.screenHeight;
		switch (mode) {
			case FADE -> {
				Draw.pushAlpha(Motion.clamp01(e));
				g.pose().pushMatrix();
				g.pose().translate(0, (1 - e) * 10);
				run(g, content);
			}
			case ZOOM -> {
				float s = 0.86f + 0.14f * e;
				Draw.pushAlpha(Motion.clamp01(p * 2.5f));
				g.pose().pushMatrix();
				g.pose().translate(w / 2f, h / 2f);
				g.pose().scale(s, s);
				g.pose().translate(-w / 2f, -h / 2f);
				run(g, content);
			}
			case GLIDE -> {
				// a soft lift into place: fades in, rises a few pixels and settles from just under full size
				float s = 0.965f + 0.035f * e;
				Draw.pushAlpha(Motion.clamp01(e * 1.3f));
				g.pose().pushMatrix();
				g.pose().translate(w / 2f, h / 2f + (1 - e) * 8);
				g.pose().scale(s, s);
				g.pose().translate(-w / 2f, -h / 2f);
				run(g, content);
			}
			case SLIDE -> {
				Draw.pushAlpha(Motion.clamp01(e));
				g.pose().pushMatrix();
				g.pose().translate(-(1 - e) * 36, 0);
				run(g, content);
			}
			case POUR -> pour(ui, p, e, content);
			case GLITCH -> glitch(ui, p, e, content);
			case CRT -> crt(ui, p, content);
			case NONE -> content.run();
		}
	}

	private static void run(GuiGraphicsExtractor g, Runnable content) {
		try {
			content.run();
		} finally {
			g.pose().popMatrix();
			Draw.popAlpha();
		}
	}

	/** The screen fills like a cup: content shows below a rising, rippling coffee surface. */
	private static void pour(Ui ui, float p, float e, Runnable content) {
		GuiGraphicsExtractor g = ui.g;
		Theme t = ui.theme;
		int w = ui.screenWidth, h = ui.screenHeight;
		float ripple = 5 * (1 - p);
		int level = Math.round(h - Motion.clamp01(e) * (h + ripple * 2 + 4));
		g.enableScissor(0, Math.max(0, level), w, h);
		try {
			content.run();
		} finally {
			g.disableScissor();
		}
		if (level <= -ripple - 3 || level >= h) return;
		float time = ui.time;
		int foam = Colors.lerp(t.accent, 0xFFFFFFFF, 0.45f);
		for (int x = 0; x < w; x += 2) {
			float wave = (float) (Math.sin(x * 0.045f + time * 7) + Math.sin(x * 0.11f - time * 5) * 0.5) * ripple;
			int top = Math.round(level + wave);
			Draw.rect(g, x, top - 1, 2, 1, Colors.withAlpha(foam, 0xB0));
			Draw.rect(g, x, top, 2, Math.max(1, level + Math.round(ripple) + 3 - top), Colors.withAlpha(t.accent, 0x90));
		}
	}

	/** The screen arrives in jittering horizontal slices that settle into place. */
	private static void glitch(Ui ui, float p, float e, Runnable content) {
		GuiGraphicsExtractor g = ui.g;
		int w = ui.screenWidth, h = ui.screenHeight;
		float chaos = (1 - Motion.clamp01(e));
		chaos *= chaos;
		int slices = 7;
		int frame = (int) (ui.time * 30);
		Draw.pushAlpha(Motion.clamp01(p * 3));
		try {
			for (int i = 0; i < slices; i++) {
				int y0 = h * i / slices, y1 = h * (i + 1) / slices;
				float dx = (Motion.hash(i, frame) - 0.5f) * 90 * chaos;
				g.enableScissor(0, y0, w, y1);
				g.pose().pushMatrix();
				g.pose().translate(dx, 0);
				try {
					content.run();
				} finally {
					g.pose().popMatrix();
					g.disableScissor();
				}
			}
		} finally {
			Draw.popAlpha();
		}
		for (int i = 0; i < 4; i++) {
			if (Motion.hash(i, frame + 3) > chaos * 1.4f) continue;
			int y = (int) (Motion.hash(i, frame) * h);
			Draw.rect(g, 0, y, w, 1 + (int) (Motion.hash(i, frame + 1) * 3), ui.theme.accent(Math.round(0x90 * chaos)));
		}
	}

	/** An old tube switching on: a bright line, then the picture opening vertically from it. */
	private static void crt(Ui ui, float p, Runnable content) {
		GuiGraphicsExtractor g = ui.g;
		int w = ui.screenWidth, h = ui.screenHeight;
		int cy = h / 2;
		float line = Ease.OUT_CUBIC.apply(Motion.range(p, 0f, 0.35f));
		float open = Ease.OUT_EXPO.apply(Motion.range(p, 0.3f, 1f));
		int glow = Colors.lerp(ui.theme.accent, 0xFFFFFFFF, 0.7f);
		if (open > 0) {
			int half = Math.max(1, Math.round(h / 2f * open));
			g.enableScissor(0, cy - half, w, cy + half);
			try {
				content.run();
			} finally {
				g.disableScissor();
			}
			Draw.rect(g, 0, 0, w, h, Colors.withAlpha(0xFFFFFF, Math.round(0x40 * (1 - open))));
		}
		if (open < 1) {
			int lw = Math.round(w * line);
			int a = Math.round(0xFF * (1 - open));
			Draw.rect(g, (w - lw) / 2, cy - 1, lw, 2, Colors.withAlpha(glow, a));
			Draw.rect(g, (w - lw) / 2, cy - 3, lw, 6, Colors.withAlpha(glow, a / 4));
		}
	}
}
