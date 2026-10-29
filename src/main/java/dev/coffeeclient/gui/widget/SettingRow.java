package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.theme.Theme;

import java.util.function.Supplier;

/** Settings-screen row: label on the left, one control right-aligned, soft highlight on hover. */
public class SettingRow extends Container implements ScrollList.Relayout {
	public static final int HEIGHT = 20;

	private final Supplier<String> label;
	private final Widget control;
	private final int controlWidth;
	private final int controlHeight;

	public SettingRow(String label, Widget control, int controlWidth) {
		this(() -> label, control, controlWidth, 14);
	}

	public SettingRow(Supplier<String> label, Widget control, int controlWidth, int controlHeight) {
		this.label = label;
		this.control = add(control);
		this.controlWidth = controlWidth;
		this.controlHeight = controlHeight;
		this.height = HEIGHT;
	}

	public String label() {
		return label.get();
	}

	@Override
	public void relayout() {
		control.setBounds(x + width - controlWidth - 4, y + (height - controlHeight) / 2, controlWidth, controlHeight);
	}

	/** Descriptions show over the label only, never on top of the control being used. */
	@Override
	protected boolean tooltipAllowed(Ui ui) {
		return ui.mouseX < control.x - 4;
	}

	@Override
	protected void draw(Ui ui) {
		relayout();
		Theme t = ui.theme;
		if (hover > 0.01f) {
			if (t.glass()) dev.coffeeclient.gui.fx.Shapes.roundRect(ui.g, x + 2, y + 1, width - 4, height - 2, 7, dev.coffeeclient.util.Colors.withAlpha(0xFFFFFF, (int) (hover * 0x0C)));
			else Draw.rect(ui.g, x, y, width, height, t.accent((int) (hover * 0x16)));
		}
		Draw.text(ui.g, Draw.trim(label.get(), width - controlWidth - 20), x + 8, y + (height - 8) / 2, t.text);
		drawChildren(ui);
	}
}
