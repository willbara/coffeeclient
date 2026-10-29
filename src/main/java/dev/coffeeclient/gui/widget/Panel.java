package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.fx.Shapes;
import dev.coffeeclient.gui.theme.Theme;

import java.util.function.Supplier;

/**
 * Titled panel: accent bar + title on the left, optional dim subtitle on the right, 1px frame, and a
 * separator under the header. Children are laid out by the owner inside {@link #contentX()} etc.
 */
public class Panel extends Container {
	public static final int HEADER = 14;
	public static final int PAD = 5;

	private final String title;
	private Supplier<String> subtitle;
	private Supplier<Integer> subtitleColor;

	public Panel(String title) {
		this.title = title;
	}

	public Panel subtitle(Supplier<String> subtitle) {
		this.subtitle = subtitle;
		return this;
	}

	/** Colour of the subtitle text (defaults to dim text). */
	public Panel subtitleColor(Supplier<Integer> color) {
		this.subtitleColor = color;
		return this;
	}

	public int contentX() {
		return x + PAD;
	}

	public int contentY() {
		return y + HEADER + 3;
	}

	public int contentWidth() {
		return width - PAD * 2;
	}

	public int contentHeight() {
		return height - HEADER - 3 - PAD + 2;
	}

	@Override
	protected void draw(Ui ui) {
		drawFrame(ui, x, y, width, height, title);
		if (subtitle != null) {
			int color = subtitleColor != null ? subtitleColor.get() : ui.theme.dimText;
			Draw.textRight(ui.g, subtitle.get(), x + width - PAD, y + 3, color);
		}
		drawHeaderExtras(ui);
		drawChildren(ui);
	}

	/** Hook for subclasses to add header content (counters, buttons). */
	protected void drawHeaderExtras(Ui ui) {
	}

	/** Shared frame drawing so non-Panel screens (map window) get the identical look. */
	public static void drawFrame(Ui ui, int x, int y, int w, int h, String title) {
		Theme t = ui.theme;
		if (t.glass()) {
			drawGlassFrame(ui, x, y, w, h, title);
			return;
		}
		Draw.panelBody(ui.g, t, x, y, w, h);
		Draw.shapeTop(ui.g, x, y, w, HEADER, t.corners.get().insets, t.panelHeader);
		// the header's lower edge carries the moving accent, faint, across the whole width
		Draw.rect(ui.g, x + 1, y + HEADER, w - 2, 1, t.border);
		if (t.gleam.get()) Draw.gradientH(ui.g, x + 1, y + HEADER, w - 2, 1, 0, 1, pos -> t.accentAt(pos, 0x70));
		Draw.accentBar(ui.g, t, x + 4, y + 3, 9);
		if (title != null) Draw.text(ui.g, title, x + 9, y + 3, t.text);
		if (t.cornerRadius() == 0) {
			// small corner studs, as in the reference panels
			Draw.rect(ui.g, x + 2, y + h - 3, 1, 1, t.border);
			Draw.rect(ui.g, x + w - 3, y + h - 3, 1, 1, t.border);
		}
		Draw.panelFinish(ui.g, t, x, y, w, h);
	}

	/** Glass card: a lit dot and the title over a hairline that fades out toward both ends. */
	private static void drawGlassFrame(Ui ui, int x, int y, int w, int h, String title) {
		Theme t = ui.theme;
		Draw.glassBody(ui.g, t, x, y, w, h, Draw.GLASS_RADIUS);
		hairline(ui, x + 6, y + HEADER, w - 12);
		if (title != null && !title.isEmpty()) {
			Shapes.glowDot(ui.g, x + 7.5f, y + 7f, 2, 3, t.accent(0x70));
			Shapes.disc(ui.g, x + 7.5f, y + 7f, 2, t.accent);
			Draw.text(ui.g, title, x + 13, y + 3, t.strongText());
		}
		Draw.glassRim(ui.g, t, x, y, w, h, Draw.GLASS_RADIUS, 0f);
	}

	/** A 1px divider in the border colour that fades out at both ends (Glass separators). */
	public static void hairline(Ui ui, int x, int y, int w) {
		int c = ui.theme.border, clear = dev.coffeeclient.util.Colors.withAlpha(c, 0);
		int half = w / 2;
		Draw.gradientH(ui.g, x, y, half, 1, clear, c);
		Draw.gradientH(ui.g, x + half, y, w - half, 1, c, clear);
	}
}
