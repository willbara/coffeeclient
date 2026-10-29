package dev.coffeeclient.gui.widget;

import java.util.ArrayList;
import java.util.List;

/**
 * A row of a form inside a {@link ScrollList}: its children are placed once by a layout callback
 * (with the row's first position and width) and then move with the row as the list scrolls.
 */
public final class FormRow extends Container implements ScrollList.Relayout {
	/** Places the row's widgets with {@link FormRow#place}, in absolute coordinates. */
	@FunctionalInterface
	public interface Layout {
		void place(FormRow row, int x, int y, int width);
	}

	private final Layout layout;
	private final List<Widget> made = new ArrayList<>();
	private final List<int[]> offsets = new ArrayList<>();
	private boolean built;
	private int builtX, builtY;

	public FormRow(int height, Layout layout) {
		this.layout = layout;
		this.height = height;
	}

	/** Called from the layout: adds {@code w} at absolute x/y (first layout only); it keeps that offset. */
	public void place(Widget w, int px, int py, int pw, int ph) {
		if (built) return;
		add(w);
		made.add(w);
		offsets.add(new int[] {px - builtX, py - builtY, pw, ph});
	}

	@Override
	public void relayout() {
		if (!built) {
			builtX = x;
			builtY = y;
			layout.place(this, x + 4, y + 2, width - 8);
			built = true;
		}
		for (int i = 0; i < made.size(); i++) {
			int[] o = offsets.get(i);
			made.get(i).setBounds(x + o[0], y + o[1], o[2], o[3]);
		}
	}

	@Override
	protected void draw(dev.coffeeclient.gui.Ui ui) {
		relayout();
		drawChildren(ui);
	}
}
