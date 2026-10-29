package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Ui;

/** Row that centres a single control horizontally (footer buttons in lists). */
public class CenteredRow extends Container implements ScrollList.Relayout {
	private final Widget control;
	private final int controlWidth;
	private final int controlHeight;

	public CenteredRow(Widget control, int controlWidth, int controlHeight, int rowHeight) {
		this.control = add(control);
		this.controlWidth = controlWidth;
		this.controlHeight = controlHeight;
		this.height = rowHeight;
	}

	@Override
	public void relayout() {
		control.setBounds(x + (width - controlWidth) / 2, y + (height - controlHeight) / 2, controlWidth, controlHeight);
	}

	@Override
	protected void draw(Ui ui) {
		relayout();
		drawChildren(ui);
	}
}
