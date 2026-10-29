package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Ui;

import java.util.ArrayList;
import java.util.List;

/** Lays a few fixed-width controls out left to right, vertically centred; {@code null} widgets are spacers. */
public class InlineGroup extends Container {
	private static final int GAP = 5;

	private record Slot(Widget widget, int width, int height) {
	}

	private final List<Slot> slots = new ArrayList<>();

	public InlineGroup slot(Widget widget, int width, int height) {
		slots.add(new Slot(widget, width, height));
		if (widget != null) add(widget);
		return this;
	}

	public int preferredWidth() {
		int w = 0;
		for (Slot s : slots) w += s.width();
		return w + GAP * Math.max(0, slots.size() - 1);
	}

	private void layout() {
		int cx = x;
		for (Slot s : slots) {
			if (s.widget() != null) s.widget().setBounds(cx, y + (height - s.height()) / 2, s.width(), s.height());
			cx += s.width() + GAP;
		}
	}

	@Override
	protected void draw(Ui ui) {
		layout();
		drawChildren(ui);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		layout();
		return super.mouseClicked(mx, my, button);
	}
}
