package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.util.Colors;

import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** {@code [◀] [ value ] [▶]} stepper. Clicking the value steps forward; scroll wheel steps either way. */
public class CycleButton extends Widget {
	private static final int ARROW_W = 11;
	private static final int GAP = 2;

	private final Supplier<String> value;
	private final IntConsumer step;

	public CycleButton(Supplier<String> value, IntConsumer step) {
		this.value = value;
		this.step = step;
	}

	@Override
	protected void draw(Ui ui) {
		Theme t = ui.theme;
		int mid = ARROW_W + GAP;
		int midW = width - 2 * mid;
		drawArrowBox(ui, x, -1);
		drawArrowBox(ui, x + width - ARROW_W, 1);
		boolean overMid = isHovered(ui) && ui.mouseX >= x + mid && ui.mouseX < x + mid + midW;
		Draw.box(ui.g, x + mid, y, midW, height, overMid ? t.controlHover : t.control, overMid ? t.accent : t.border);
		Draw.textCentered(ui.g, Draw.trim(value.get(), midW - 4), x + mid + midW / 2, y + (height - 8) / 2, t.text);
	}

	private void drawArrowBox(Ui ui, int bx, int dir) {
		Theme t = ui.theme;
		boolean over = ui.hoverAllowed && ui.mouseX >= bx && ui.mouseX < bx + ARROW_W && ui.mouseY >= y && ui.mouseY < y + height;
		Draw.box(ui.g, bx, y, ARROW_W, height, over ? t.controlHover : t.control, over ? t.accent : t.border);
		Draw.arrow(ui.g, bx + 4, y + height / 2 - 3, 4, dir, over ? t.strongText() : Colors.lerp(t.text, t.dimText, 0.3f));
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (mx < x + ARROW_W) step.accept(-1);
		else if (mx >= x + width - ARROW_W) step.accept(1);
		else step.accept(button == RIGHT ? -1 : 1);
		return true;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		step.accept(amount > 0 ? 1 : -1);
		return true;
	}
}
