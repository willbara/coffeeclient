package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.fx.Shapes;
import dev.coffeeclient.gui.theme.Theme;

import java.util.function.BooleanSupplier;

/**
 * Section title strip inside scroll lists. Level 0 is the accent-barred strip; level 1 is a lighter,
 * indented sub-header. If given a toggle action, clicking collapses/expands the section.
 */
public class SectionHeader extends Widget {
	public static final int HEIGHT = 18;
	public static final int SUB_HEIGHT = 14;

	private final String title;
	private final int level;
	private BooleanSupplier collapsed = () -> false;
	private Runnable onToggle;
	private String badge;

	public SectionHeader(String title) {
		this(title, 0);
	}

	public SectionHeader(String title, int level) {
		this.title = title;
		this.level = level;
		this.height = level == 0 ? HEIGHT : SUB_HEIGHT;
	}

	public String title() {
		return title;
	}

	public SectionHeader collapsible(BooleanSupplier collapsed, Runnable onToggle) {
		this.collapsed = collapsed;
		this.onToggle = onToggle;
		return this;
	}

	/** Small dim text on the right (e.g. an item count). */
	public SectionHeader badge(String badge) {
		this.badge = badge;
		return this;
	}

	@Override
	protected void draw(Ui ui) {
		Theme t = ui.theme;
		boolean closed = collapsed.getAsBoolean();
		if (level == 0 && t.glass()) {
			// a lit dot and the title over a fading hairline; hover is a faint rounded wash
			if (hover > 0.01f && onToggle != null) Shapes.roundRect(ui.g, x + 2, y + 2, width - 4, height - 3, 6, dev.coffeeclient.util.Colors.withAlpha(0xFFFFFF, (int) (0x0C * hover)));
			Panel.hairline(ui, x + 4, y + height - 1, width - 8);
			Shapes.glowDot(ui.g, x + 8.5f, y + 9.5f, 2, 3, t.accent(0x60));
			Shapes.disc(ui.g, x + 8.5f, y + 9.5f, 2, t.accent);
			Draw.text(ui.g, title, x + 14, y + 6, t.strongText());
		} else if (level == 0) {
			Draw.rect(ui.g, x, y, width, 1, t.border);
			Draw.rect(ui.g, x, y + 1, width, height - 1, t.panelHeader);
			if (t.gleam.get()) Draw.gradientH(ui.g, x, y + 1, width / 2, height - 1, t.accent(0x1C), t.accent(0));
			if (hover > 0.01f && onToggle != null) Draw.rect(ui.g, x, y + 1, width, height - 1, t.accent((int) (0x14 * hover)));
			Draw.accentBar(ui.g, t, x + 4, y + 4, height - 7);
			Draw.text(ui.g, title, x + 10, y + 5, t.accent);
		} else {
			if (hover > 0.01f && onToggle != null) {
				if (t.glass()) Shapes.roundRect(ui.g, x + 2, y + 1, width - 4, height - 2, 6, dev.coffeeclient.util.Colors.withAlpha(0xFFFFFF, (int) (0x0A * hover)));
				else Draw.rect(ui.g, x, y, width, height, t.accent((int) (0x12 * hover)));
			}
			if (t.glass()) Shapes.disc(ui.g, x + 9.5f, y + height / 2f, 1, t.accent(0xC0));
			else Draw.rect(ui.g, x + 8, y + height / 2, 3, 1, t.accent(0xA0));
			Draw.text(ui.g, title, x + 14, y + (height - 8) / 2, hover > 0.5f ? t.text : t.dimText);
		}
		int right = x + width - 8;
		if (onToggle != null) {
			int c = hover > 0.5f ? t.accent : t.dimText;
			int ay = y + height / 2;
			if (closed) Draw.arrow(ui.g, right - 3, ay - 3, 4, 1, c);
			else Draw.arrowDown(ui.g, right - 5, ay - 1, 4, c);
			right -= 12;
		}
		if (badge != null) Draw.textRight(ui.g, badge, right, y + (height - 8) / 2 + (level == 0 ? 1 : 0), t.dimText);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (onToggle == null) return false;
		onToggle.run();
		return true;
	}
}
