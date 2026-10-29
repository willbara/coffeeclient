package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.util.Colors;

import java.util.function.Supplier;

/** A small framed colour square. Optionally clickable (e.g. preset colours, opening a picker). */
public class ColorSwatch extends Widget {
	private final Supplier<Integer> color;
	private final Runnable onClick;
	private Supplier<Boolean> selected = () -> false;

	public ColorSwatch(Supplier<Integer> color, Runnable onClick) {
		this.color = color;
		this.onClick = onClick;
	}

	public ColorSwatch selected(Supplier<Boolean> selected) {
		this.selected = selected;
		return this;
	}

	@Override
	protected void draw(Ui ui) {
		boolean sel = selected.get();
		Draw.rect(ui.g, x, y, width, height, Colors.withAlpha(color.get(), 255));
		Draw.border(ui.g, x, y, width, height, sel ? 0xFFFFFFFF : Colors.lerp(ui.theme.border, ui.theme.accent, hover));
		if (sel) Draw.border(ui.g, x + 1, y + 1, width - 2, height - 2, 0xFF000000);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (onClick == null || button != LEFT) return false;
		onClick.run();
		return true;
	}
}
