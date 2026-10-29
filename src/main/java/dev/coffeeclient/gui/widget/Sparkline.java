package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.util.Colors;

import java.util.function.DoubleSupplier;

/**
 * Small live graph of a sampled value (FPS on the dashboard): an accent line over a soft fill, with the
 * newest sample marked. History lives in the instance passed in, so it survives screen rebuilds.
 */
public class Sparkline extends Widget {
	/** Ring buffer of samples, sampled at a fixed interval while anything draws it. */
	public static final class History {
		private final double[] values;
		private final DoubleSupplier source;
		private final long intervalMs;
		private int head;
		private int count;
		private long last;

		public History(int size, long intervalMs, DoubleSupplier source) {
			this.values = new double[size];
			this.intervalMs = intervalMs;
			this.source = source;
		}

		void sample() {
			long now = System.currentTimeMillis();
			if (now - last < intervalMs) return;
			last = now;
			values[head] = source.getAsDouble();
			head = (head + 1) % values.length;
			count = Math.min(values.length, count + 1);
		}

		double get(int i) {
			return values[(head - count + i + values.length * 2) % values.length];
		}
	}

	private final History history;
	private final String unit;

	public Sparkline(History history, String unit) {
		this.history = history;
		this.unit = unit;
	}

	@Override
	protected void draw(Ui ui) {
		Theme t = ui.theme;
		history.sample();
		Draw.rect(ui.g, x, y, width, height, Colors.withAlpha(t.control, 0x80));
		int n = history.count;
		if (n < 2) return;
		double max = 1, min = Double.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			max = Math.max(max, history.get(i));
			min = Math.min(min, history.get(i));
		}
		double lo = Math.max(0, min - (max - min) * 0.2);
		float stepX = (width - 2) / (float) (history.values.length - 1);
		float startX = x + 1 + (history.values.length - n) * stepX;
		float px = 0, py = 0;
		for (int i = 0; i < n; i++) {
			float cx = startX + i * stepX;
			float cy = y + height - 2 - (float) ((history.get(i) - lo) / Math.max(1e-6, max - lo)) * (height - 4);
			int col = Math.round(cx);
			float pos = i / (float) n;
			Draw.gradientV(ui.g, col, Math.round(cy), Math.max(1, Math.round(stepX)), y + height - 1 - Math.round(cy),
				t.accentAt(pos, 0x50), t.accentAt(pos, 0x08));
			if (i > 0) Draw.stroke(ui.g, px, py, cx, cy, 1.2f, t.accentAt(pos));
			px = cx;
			py = cy;
		}
		Draw.rect(ui.g, Math.round(px) - 1, Math.round(py) - 1, 3, 3, Colors.lerp(t.accent, 0xFFFFFFFF, 0.5f));
		String label = Math.round(history.get(n - 1)) + " " + unit + "  ·  max " + Math.round(max);
		// the line peaks right under the label; a backing keeps it readable
		int lw = Draw.width(label);
		Draw.rect(ui.g, x + width - 4 - lw, y, lw + 4, 10, Colors.withAlpha(t.control, 0xC0));
		Draw.textRight(ui.g, label, x + width - 2, y + 1, Colors.withAlpha(t.dimText, 0xC0));
	}
}
