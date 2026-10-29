package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.gui.fx.Shapes;
import dev.coffeeclient.gui.anim.Anim;
import dev.coffeeclient.gui.anim.Ease;
import dev.coffeeclient.gui.anim.Motion;
import dev.coffeeclient.gui.anim.Tween;
import dev.coffeeclient.gui.UiState;
import dev.coffeeclient.gui.theme.Theme;

import java.util.ArrayList;
import java.util.List;

/** Popup list used as an overlay by {@link Dropdown} and right-click context menus. */
public class MenuList extends Widget {
	public static final int ROW_H = 12;

	public record Entry(String label, Runnable action, Integer swatch, boolean danger) {
		public static Entry of(String label, Runnable action) {
			return new Entry(label, action, null, false);
		}
	}

	private final List<Entry> entries = new ArrayList<>();
	private String header;
	private final Tween open = new Tween();
	private final Anim highlight = new Anim(22f);

	public MenuList add(Entry e) {
		entries.add(e);
		return this;
	}

	public MenuList add(String label, Runnable action) {
		return add(Entry.of(label, action));
	}

	public MenuList header(String header) {
		this.header = header;
		return this;
	}

	/** Size to content and place at (px, py), clamped to the screen. */
	public MenuList openAt(int px, int py, int screenW, int screenH, int minWidth) {
		int w = minWidth;
		for (Entry e : entries) w = Math.max(w, Draw.width(e.label()) + (e.swatch() != null ? 20 : 10));
		if (header != null) w = Math.max(w, Draw.width(header) + 10);
		int h = entries.size() * ROW_H + 4 + (header != null ? ROW_H : 0);
		setBounds(Math.max(1, Math.min(px, screenW - w - 1)), Math.max(1, Math.min(py, screenH - h - 1)), w, h);
		open.start(0.22f);
		UiState.openOverlay(this);
		return this;
	}

	@Override
	protected void draw(Ui ui) {
		Theme t = ui.theme;
		float p = t.motion() ? open.eased(Ease.OUT_CUBIC) : 1f;
		int shownH = Math.max(4, Math.round(height * p));
		Draw.shadow(ui.g, t, x, y, width, shownH);
		Draw.box(ui.g, x, y, width, shownH, t.panelHeader, t.glass() ? Colors.withAlpha(Colors.lerp(t.border, 0xFFFFFFFF, 0.3f), 0xC0) : t.accent(0xC0));
		ui.g.enableScissor(x, y, x + width, y + shownH);
		try {
			drawEntries(ui, t, p);
		} finally {
			ui.g.disableScissor();
		}
	}

	private void drawEntries(Ui ui, Theme t, float p) {
		int ry = y + 2;
		if (header != null) {
			Draw.text(ui.g, header, x + 5, ry + 2, t.dimText);
			ry += ROW_H;
		}
		int hoverY = -1;
		int scan = ry;
		for (int i = 0; i < entries.size(); i++, scan += ROW_H) {
			if (ui.mouseX >= x && ui.mouseX < x + width && ui.mouseY >= scan && ui.mouseY < scan + ROW_H) hoverY = scan;
		}
		// the highlight glides between rows
		if (hoverY >= 0) {
			int hy = Math.round(highlight.update(ui, hoverY));
			if (t.glass()) {
				Shapes.roundRect(ui.g, x + 2, hy, width - 4, ROW_H, ROW_H / 2, t.accent(0x30));
			} else {
				Draw.rect(ui.g, x + 1, hy, width - 2, ROW_H, t.accent(0x50));
				Draw.rect(ui.g, x + 1, hy, 1, ROW_H, t.accent);
			}
		}
		int index = 0;
		for (Entry e : entries) {
			boolean over = ry == hoverY;
			float in = Motion.range(p, index * 0.06f, index * 0.06f + 0.5f);
			index++;
			Draw.pushAlpha(in);
			int tx = x + 5;
			if (e.swatch() != null) {
				Draw.box(ui.g, tx, ry + 2, 8, 8, e.swatch(), t.border);
				tx += 12;
			}
			Draw.text(ui.g, e.label(), tx + Math.round((1 - in) * 6), ry + 2, e.danger() ? t.error : over ? t.strongText() : t.text);
			Draw.popAlpha();
			ry += ROW_H;
		}
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		int ry = y + 2 + (header != null ? ROW_H : 0);
		int index = (int) ((my - ry) / ROW_H);
		UiState.closeOverlay();
		if (my >= ry && index >= 0 && index < entries.size()) entries.get(index).action().run();
		return true;
	}
}
