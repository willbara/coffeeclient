package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.anim.Ease;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.util.Colors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Themed hover tooltip. The first line is the title (bright); following lines are dim. Lines starting
 * with {@code "§a"} use the accent colour (used for "source" lines on the map). Lines longer than
 * {@link #MAX_WORDS} words wrap onto more lines.
 */
public final class Tooltip {
	public static final String ACCENT = "§a";
	/** Most words kept on one tooltip line. */
	private static final int MAX_WORDS = 7;
	private static List<String> shownLines;
	private static long shownSince;
	private static long lastDrawn;

	private Tooltip() {
	}

	public static void draw(Ui ui, List<String> lines, int mx, int my) {
		if (lines == null || lines.isEmpty()) return;
		lines = wrap(lines);
		Theme t = ui.theme;
		// a new tooltip (or one after a gap) fades and slides in
		long now = System.currentTimeMillis();
		if (!lines.equals(shownLines) || now - lastDrawn > 150) {
			shownLines = lines;
			shownSince = now;
		}
		lastDrawn = now;
		float in = t.motion() ? Ease.OUT_CUBIC.apply((now - shownSince) / 140f) : 1f;
		int w = 0;
		for (String l : lines) w = Math.max(w, Draw.width(strip(l)));
		int h = lines.size() * 10 + 4;
		int screenW = ui.screenWidth, screenH = ui.screenHeight;
		int tx = mx + 10, ty = my - 4;
		if (tx + w + 8 > screenW) tx = mx - w - 14;
		if (ty + h + 2 > screenH) ty = screenH - h - 2;
		tx = Math.max(2, tx);
		ty = Math.max(2, ty);
		ui.g.nextStratum();
		ty += Math.round((1 - in) * 4);
		Draw.pushAlpha(in);
		try {
			Draw.shadow(ui.g, t, tx, ty, w + 8, h);
			Draw.box(ui.g, tx, ty, w + 8, h, Colors.withAlpha(Colors.shade(t.panel, 0.8f), 0xF4), t.border);
			if (!t.glass()) Draw.gradientV(ui.g, tx + 1, ty + 1, 1, h - 2, t.accentAt(0f, 0xC0), t.accentAt(0.3f, 0x50));
			for (int i = 0; i < lines.size(); i++) {
				String l = lines.get(i);
				int color = i == 0 ? t.strongText() : l.startsWith(ACCENT) ? t.accent : t.dimText;
				Draw.text(ui.g, strip(l), tx + 5, ty + 3 + i * 10, color);
			}
		} finally {
			Draw.popAlpha();
		}
	}

	/** Splits each line into runs of at most {@link #MAX_WORDS} words; continuation lines keep the accent colour. */
	private static List<String> wrap(List<String> lines) {
		List<String> out = new ArrayList<>();
		for (String l : lines) {
			String[] words = strip(l).trim().split("\\s+");
			if (words.length <= MAX_WORDS) {
				out.add(l);
				continue;
			}
			String accent = l.startsWith(ACCENT) ? ACCENT : "";
			for (int i = 0; i < words.length; i += MAX_WORDS) {
				out.add(accent + String.join(" ", Arrays.copyOfRange(words, i, Math.min(words.length, i + MAX_WORDS))));
			}
		}
		return out;
	}

	private static String strip(String l) {
		return l.startsWith(ACCENT) ? l.substring(ACCENT.length()) : l;
	}
}
