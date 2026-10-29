package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.anim.Motion;
import dev.coffeeclient.gui.anim.Tween;
import dev.coffeeclient.gui.fx.Shapes;
import dev.coffeeclient.gui.fx.UiSounds;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.util.Colors;

import java.util.function.BooleanSupplier;

/** Square checkbox filled with the accent when on; in the Glass style a pill switch with a gliding knob. */
public class Toggle extends Widget {
	private final BooleanSupplier state;
	private final Runnable toggle;
	private float on = -1;
	/** 0..1 since the last click, for the pop. */
	private final Tween pop = new Tween();
	private boolean leftAligned;

	public Toggle(BooleanSupplier state, Runnable toggle) {
		this.state = state;
		this.toggle = toggle;
	}

	/** Sit at the left of wide bounds (a label follows on the right) instead of the right. */
	public Toggle leftAligned() {
		this.leftAligned = true;
		return this;
	}

	/** Width of the Glass switch, which reaches left past narrow bounds. */
	private static final int SWITCH_W = 20;

	@Override
	public boolean contains(double mx, double my) {
		if (!ui().glass() || width >= SWITCH_W || leftAligned) return super.contains(mx, my);
		return mx >= x + width - SWITCH_W && mx < x + width && my >= y && my < y + height;
	}

	@Override
	protected void draw(Ui ui) {
		Theme t = ui.theme;
		float target = state.getAsBoolean() ? 1 : 0;
		on = on < 0 ? target : on + (target - on) * ui.animStep(16f);
		if (t.glass()) {
			drawSwitch(ui, t);
			return;
		}
		int size = Math.min(width, height);
		int bx = leftAligned ? x : x + width - size;
		// press squash, then the fill pops in with the theme's motion curve
		float squash = pop.started() ? 1 - Motion.range(pop.progress(), 0f, 0.35f) : 0f;
		int sq = Math.round(squash * squash);
		Draw.box(ui.g, bx + sq, y + sq, size - sq * 2, size - sq * 2, t.control, Colors.lerp(t.border, t.accent, Math.max(on, hover * 0.6f)));
		if (on > 0.02f) {
			float grow = pop.started() && state.getAsBoolean() ? t.motionStyle.get().ease.apply(pop.progress()) : 1f;
			float inner = (size - 4) * Math.max(0f, Math.min(1.25f, grow));
			float cx = bx + size / 2f, cy = y + size / 2f;
			int half = Math.max(1, Math.round(inner / 2));
			Draw.rect(ui.g, Math.round(cx) - half, Math.round(cy) - half, half * 2, half * 2, Colors.fade(t.accent, on));
		}
	}

	/** A pill track that fills with the accent, a white knob that glides across, and a soft glow while on. */
	private void drawSwitch(Ui ui, Theme t) {
		int sh = Math.min(height, 10), sw = Math.min(SWITCH_W, Math.max(width, sh * 2));
		int sx = leftAligned ? x : x + width - sw, sy = y + (height - sh) / 2;
		float glide = pop.started() ? t.motionStyle.get().ease.apply(pop.progress()) : 1f;
		float knob = state.getAsBoolean() ? glide : 1 - glide;
		if (!pop.started() || pop.done()) knob = on;
		if (on > 0.02f) Shapes.halo(ui.g, sx, sy, sw, sh, sh / 2, 4, t.accent(Math.round(0x80 * on * Math.max(0.35f, t.glow.floatValue()))));
		int track = Colors.lerp(Colors.lerp(t.control, 0xFFFFFFFF, 0.04f + hover * 0.05f), t.accent(0xD8), on);
		Shapes.roundRect(ui.g, sx, sy, sw, sh, sh / 2, track);
		Shapes.roundOutline(ui.g, sx, sy, sw, sh, sh / 2, Colors.lerp(Colors.withAlpha(0xFFFFFF, 0x30), t.accent, on), Colors.withAlpha(0xFFFFFF, 0x10));
		int kr = sh / 2 - 1;
		float kx = sx + 1 + kr + knob * (sw - 2 - kr * 2), ky = sy + sh / 2f;
		Shapes.disc(ui.g, kx, ky + 0.5f, kr, Colors.withAlpha(0, 0x50));
		Shapes.disc(ui.g, kx, ky, kr, Colors.lerp(0xFFD8DCD9, 0xFFFFFFFF, on));
	}

	private static dev.coffeeclient.gui.theme.Theme ui() {
		return dev.coffeeclient.core.CoffeeClient.theme();
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button != LEFT) return false;
		toggle.run();
		pop.start(0.32f / ui().animationSpeed.floatValue());
		UiSounds.toggle(state.getAsBoolean());
		return true;
	}
}
