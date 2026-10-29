package dev.coffeeclient.gui.fx;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.anim.Ease;
import dev.coffeeclient.util.Colors;

/** Expanding rings from click points. Each widget owns one; draw it inside the widget's clip. */
public final class Ripple {
	private static final float SECONDS = 0.45f;
	private final float[] xs = new float[3];
	private final float[] ys = new float[3];
	private final long[] starts = new long[3];
	private int next;

	public void spawn(double x, double y) {
		xs[next] = (float) x;
		ys[next] = (float) y;
		starts[next] = System.nanoTime();
		next = (next + 1) % xs.length;
	}

	/** Draws live rings; {@code maxR} is the radius a ring reaches as it fades out. */
	public void draw(Ui ui, int color, float maxR) {
		if (!ui.theme.motion()) return;
		long now = System.nanoTime();
		for (int i = 0; i < xs.length; i++) {
			if (starts[i] == 0) continue;
			float p = (now - starts[i]) / 1e9f / SECONDS;
			if (p >= 1) {
				starts[i] = 0;
				continue;
			}
			float r = 2 + Ease.OUT_CUBIC.apply(p) * maxR;
			int a = Math.round(Colors.alpha(color) * (1 - p));
			Draw.ring(ui.g, xs[i], ys[i], r, 1.2f, Colors.withAlpha(color, a));
			Draw.ring(ui.g, xs[i], ys[i], r * 0.6f, 1f, Colors.withAlpha(color, a / 3));
		}
	}
}
