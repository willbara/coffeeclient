package dev.coffeeclient.hud.elements;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.module.modules.client.HudModule;

import java.util.List;

/** Small panel of "label value" lines (coordinates, FPS). */
public abstract class TextLinesElement extends HudElement {
	protected static final int LINE_H = 10;

	protected TextLinesElement(String id, String displayName) {
		super(id, displayName);
	}

	/**
	 * Default y for the n-th one-line element stacked down the left edge.
	 */
	static int stackY(int slot) {
		return MARGIN + slot * (gridded(LINE_H + 4) + GRID);
	}

	/** Round up to the HUD grid so stacked elements line up on it. */
	static int gridded(int size) {
		return (size + GRID - 1) / GRID * GRID;
	}

	protected boolean background() {
		return hud().backgrounds.get();
	}

	protected HudModule hud() {
		return CoffeeClient.modules().get(HudModule.class);
	}

	/** Pairs of label and value. */
	protected abstract List<String[]> lines();

	@Override
	public int width() {
		int w = 20;
		for (String[] l : lines()) w = Math.max(w, Draw.width(l[0]) + 4 + Draw.width(l[1]));
		return gridded(w + 10);
	}

	@Override
	public int height() {
		return gridded(lines().size() * LINE_H + 4);
	}

	@Override
	public void render(Ui ui, int x, int y, boolean preview) {
		Theme t = ui.theme;
		int w = width(), h = height();
		if (background()) Draw.hudBackground(ui.g, x, y, w, h, hud().backgroundColor.argb());
		List<String[]> lines = lines();
		int ly = y + (h - lines.size() * LINE_H) / 2;
		for (int i = 0; i < lines.size(); i++) {
			String[] l = lines.get(i);
			Draw.text(ui.g, l[0], x + 5, ly + 1, t.dimText);
			Draw.text(ui.g, l[1], x + 5 + Draw.width(l[0]) + 4, ly + 1, valueColor(ui, i));
			ly += LINE_H;
		}
	}

	/** Colour of a line's value; override for status colouring. */
	protected int valueColor(Ui ui, int line) {
		return ui.theme.text;
	}
}
