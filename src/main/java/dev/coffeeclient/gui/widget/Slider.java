package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.anim.Anim;
import dev.coffeeclient.gui.fx.Shapes;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Colors;

import java.util.function.Supplier;

/**
 * Horizontal slider bound to a {@link NumberSetting}: coloured track fill, knob, numeric readout, and an
 * optional colour swatch at the far right (used by the RGB theme sliders).
 */
public class Slider extends Widget {
	private static final int READOUT_W = 24;
	private static final int SWATCH_W = 11;

	private final NumberSetting setting;
	private Supplier<Integer> trackColor;
	private Supplier<Integer> swatch;
	private boolean showReadout = true;
	private boolean dragging;
	private final Anim shown = new Anim(20f);
	private final Anim bubble = new Anim(18f);
	private final Anim grip = new Anim(16f);

	public Slider(NumberSetting setting) {
		this.setting = setting;
	}

	public Slider trackColor(Supplier<Integer> color) {
		this.trackColor = color;
		return this;
	}

	public Slider swatch(Supplier<Integer> color) {
		this.swatch = color;
		return this;
	}

	public Slider compact() {
		this.showReadout = false;
		return this;
	}

	private int trackX() {
		return x + 2;
	}

	private int trackW() {
		int w = width - 4;
		if (showReadout) w -= READOUT_W + 4;
		if (swatch != null) w -= SWATCH_W + 3;
		return Math.max(10, w);
	}

	@Override
	protected void draw(Ui ui) {
		Theme t = ui.theme;
		int tx = trackX(), tw = trackW();
		int cy = y + height / 2;
		Draw.box(ui.g, tx - 1, cy - 2, tw + 2, 4, t.control, Colors.lerp(t.border, t.accent, dragging ? 1 : hover * 0.6f));
		float frac = shown.update(ui, (float) setting.fraction());
		int fill = Math.round(tw * frac);
		int kx = tx + fill - 1;
		if (t.glass()) {
			// a rounded fill and a white knob that glows while it is held
			Shapes.roundRect(ui.g, tx, cy - 1, fill, 2, 1, trackColor != null ? trackColor.get() : t.accent);
			float held = grip.update(ui, dragging ? 1f : hover > 0.5f ? 0.5f : 0f);
			float kc = tx + fill;
			if (held > 0.02f) Shapes.glowDot(ui.g, kc, cy, 4, 5, t.accent(Math.round(0xA0 * held)));
			Shapes.disc(ui.g, kc, cy + 0.6f, 4, Colors.withAlpha(0, 0x60));
			Shapes.disc(ui.g, kc, cy, 4, 0xFFF4F6F5);
			Shapes.disc(ui.g, kc, cy, 1, Colors.lerp(0xFFF4F6F5, t.accent, held));
		} else {
			if (trackColor != null) Draw.rect(ui.g, tx, cy - 1, fill, 2, trackColor.get());
			else Draw.gradientH(ui.g, tx, cy - 1, fill, 2, 0, frac * 0.5f, pos -> t.accentAt(pos));
			Draw.rect(ui.g, kx, cy - 5, 3, 10, dragging || hover > 0.5f ? 0xFFFFFFFF : t.strongText());
			Draw.border(ui.g, kx - 1, cy - 6, 5, 12, Colors.withAlpha(0, 0x90));
		}
		// value bubble pops up over the knob while dragging
		float b = bubble.update(ui, dragging ? 1f : 0f);
		if (b > 0.02f) {
			String v = setting.display();
			int bw = Draw.width(v) + 6;
			float s = t.motionStyle.get().ease.apply(b);
			int by = cy - 8 - Math.round(10 * s);
			Draw.pushAlpha(Math.min(1f, b * 1.5f));
			Draw.box(ui.g, kx + 1 - bw / 2, by, bw, 11, t.panelHeader, t.accent);
			Draw.text(ui.g, v, kx + 4 - bw / 2, by + 2, t.strongText());
			Draw.popAlpha();
		}
		int rx = tx + tw + 5;
		if (showReadout) {
			Draw.textRight(ui.g, setting.display(), rx + READOUT_W, y + (height - 8) / 2, t.text);
			rx += READOUT_W + 3;
		}
		if (swatch != null) Draw.box(ui.g, rx, cy - 5, SWATCH_W - 1, 10, swatch.get(), t.border);
		if (isHovered(ui) || dragging) ui.tooltip(setting.name() + ": " + setting.display());
	}

	private void apply(double mx) {
		setting.setFraction((mx - trackX()) / (double) trackW());
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button != LEFT || mx > trackX() + trackW() + 2) return false;
		dragging = true;
		apply(mx);
		return true;
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (!dragging) return false;
		apply(mx);
		return true;
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		boolean was = dragging;
		dragging = false;
		return was;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		setting.stepBy(amount > 0 ? 1 : -1);
		return true;
	}
}
