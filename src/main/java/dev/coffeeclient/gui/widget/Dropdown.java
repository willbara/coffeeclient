package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.UiState;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.util.Colors;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Compact selector button that opens a {@link MenuList} overlay of options. */
public class Dropdown<T> extends Widget {
	private final Supplier<List<T>> options;
	private final Supplier<T> current;
	private final Function<T, String> label;
	private final Consumer<T> onSelect;

	public Dropdown(Supplier<List<T>> options, Supplier<T> current, Function<T, String> label, Consumer<T> onSelect) {
		this.options = options;
		this.current = current;
		this.label = label;
		this.onSelect = onSelect;
	}

	@Override
	protected void draw(Ui ui) {
		Theme t = ui.theme;
		boolean open = UiState.overlay() instanceof MenuList && hover > 0;
		Draw.box(ui.g, x, y, width, height, Colors.lerp(t.control, t.controlHover, hover), Colors.lerp(t.border, t.accent, open ? 1 : hover));
		T value = current.get();
		Draw.text(ui.g, Draw.trim(value == null ? "-" : label.apply(value), width - 16), x + 4, y + (height - 8) / 2, t.text);
		Draw.arrowDown(ui.g, x + width - 10, y + height / 2 - 1, 3, t.dimText);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		MenuList menu = new MenuList();
		for (T option : options.get()) menu.add(label.apply(option), () -> onSelect.accept(option));
		menu.openAt(x, y + height + 1, UiState.screenWidth(), UiState.screenHeight(), width);
		return true;
	}
}
