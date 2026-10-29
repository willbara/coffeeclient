package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;

import java.util.function.Function;
import java.util.function.Supplier;

/** A line of text. Colour is resolved from the theme each frame so it follows live recolouring. */
public class Label extends Widget {
	public enum Style {
		TITLE, TEXT, DIM, ACCENT
	}

	private final Supplier<String> text;
	private final Style style;
	private Function<Ui, Integer> color;
	private int indent;

	public Label(String text, Style style) {
		this(() -> text, style);
	}

	public Label(Supplier<String> text, Style style) {
		this.text = text;
		this.style = style;
		this.height = 12;
	}

	public Label indent(int indent) {
		this.indent = indent;
		return this;
	}

	public Label color(Function<Ui, Integer> color) {
		this.color = color;
		return this;
	}

	@Override
	protected void draw(Ui ui) {
		int c = color != null ? color.apply(ui) : switch (style) {
			case TITLE -> ui.theme.glass() ? ui.theme.strongText() : ui.theme.accent;
			case TEXT -> ui.theme.text;
			case DIM -> ui.theme.dimText;
			case ACCENT -> ui.theme.accent;
		};
		Draw.text(ui.g, Draw.trim(text.get(), Math.max(10, width - indent)), x + indent, y + (height - 8) / 2, c);
	}
}
