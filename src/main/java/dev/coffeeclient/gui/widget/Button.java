package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.anim.Ease;
import dev.coffeeclient.gui.fx.Ripple;
import dev.coffeeclient.gui.fx.UiSounds;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.util.Colors;

import java.util.function.Supplier;

/** Plain text button (Back, Reset, Save, ...), or a small icon button with {@link #icon}. */
public class Button extends Widget {
	private final Supplier<String> label;
	private final Runnable action;
	private boolean danger;
	private IconButton.Icon icon;
	private final Ripple ripple = new Ripple();

	public Button(String label, Runnable action) {
		this(() -> label, action);
	}

	public Button(Supplier<String> label, Runnable action) {
		this.label = label;
		this.action = action;
	}

	/** Red-tinted text for destructive actions. */
	public Button danger() {
		this.danger = true;
		return this;
	}

	/** Draws {@code icon} centred instead of the label (give it a tooltip saying what it does). */
	public Button icon(IconButton.Icon icon) {
		this.icon = icon;
		return this;
	}

	@Override
	protected void draw(Ui ui) {
		Theme t = ui.theme;
		Draw.box(ui.g, x, y, width, height, Colors.lerp(t.control, t.controlHover, hover), Colors.lerp(t.border, t.accent, hover));
		// accent underline grows from the centre on hover (Glass pills light their rim instead)
		int uw = t.glass() ? 0 : Math.round((width - 4) * Ease.OUT_CUBIC.apply(hover));
		if (uw > 0) Draw.rect(ui.g, x + (width - uw) / 2, y + height - 2, uw, 1, t.accent(Math.round(0xC0 * hover)));
		ui.g.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1);
		ripple.draw(ui, Colors.withAlpha(t.strongText(), 0x80), width * 0.5f);
		ui.g.disableScissor();
		if (icon != null) {
			// items ignore the interface alpha, so hold them back until a fade-in is mostly done; they hop up on hover
			if (Draw.alpha() > 0.6f) icon.draw(ui, x + (width - 16) / 2, y + (height - 16) / 2 - Math.round(Ease.OUT_BACK.apply(hover)));
			return;
		}
		int color = danger ? Colors.lerp(t.text, t.error, 0.7f) : (hover > 0.5f ? t.strongText() : t.text);
		Draw.textCentered(ui.g, Draw.trim(label.get(), width - 6), x + width / 2, y + (height - 8) / 2, color);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button != LEFT) return false;
		ripple.spawn(mx, my);
		UiSounds.click(1f);
		action.run();
		return true;
	}
}
