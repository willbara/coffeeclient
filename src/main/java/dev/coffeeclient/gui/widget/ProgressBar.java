package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.fx.Shapes;
import dev.coffeeclient.util.Colors;

import java.util.function.DoubleSupplier;

/** Thin track with a gold (accent) fill that eases toward its target. */
public class ProgressBar extends Widget {
	private final DoubleSupplier fraction;
	private float shown = -1;

	public ProgressBar(DoubleSupplier fraction) {
		this.fraction = fraction;
	}

	@Override
	protected void draw(Ui ui) {
		float target = (float) Math.max(0, Math.min(1, fraction.getAsDouble()));
		shown = shown < 0 ? target : shown + (target - shown) * ui.animStep(6f);
		int fill = Math.round(width * shown);
		var t = ui.theme;
		if (t.glass()) {
			Shapes.roundRect(ui.g, x, y, width, height, height / 2, Colors.withAlpha(0xFFFFFF, 0x14));
			if (fill > 0) Shapes.halo(ui.g, x, y, Math.max(height, fill), height, height / 2, 3, t.accent(Math.round(0x50 * Math.max(0.35f, t.glow.floatValue()))));
			Shapes.roundRect(ui.g, x, y, Math.max(fill > 0 ? height : 0, fill), height, height / 2, t.accent);
			return;
		}
		Draw.rect(ui.g, x, y, width, height, ui.theme.control);
		Draw.gradientH(ui.g, x, y, fill, height, 0, shown * 0.5f, pos -> t.accentAt(pos));
		Draw.rect(ui.g, x, y, fill, 1, Colors.withAlpha(0xFFFFFF, 0x40));
		if (t.sheen.get() && fill > 6) Draw.sheen(ui.g, x, y, fill, height, Draw.sheenPhase(ui.timeMs, 2.5f, 0), Colors.withAlpha(0xFFFFFF, 0x30));
	}
}
