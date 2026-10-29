package dev.coffeeclient.hud.elements;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.module.modules.client.HudModule;
import dev.coffeeclient.util.StarscriptUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Free-form text driven by Starscript templates ({@code {player.pos.x}}, {@code {fps}}, ...). Each
 * configured line is evaluated and drawn as its own row.
 */
public final class TextElement extends HudElement {
	private static final int LINE_H = 10;
	/** Templates are re-evaluated at most this often (ms); the HUD redraws every frame. */
	private static final long REFRESH_MS = 250;

	private final List<String> lines = new ArrayList<>();
	private long refreshedAt;

	public TextElement() {
		super("text", "Text");
	}

	private HudModule hud() {
		return CoffeeClient.modules().get(HudModule.class);
	}

	@Override
	public boolean isEnabled() {
		return hud().isEnabled() && hud().text.get();
	}

	@Override
	public void toggle() {
		hud().text.toggle();
	}

	private void refresh() {
		long now = System.currentTimeMillis();
		if (now - refreshedAt < REFRESH_MS) return;
		refreshedAt = now;
		lines.clear();
		for (String template : hud().textLines.get()) {
			String out = StarscriptUtils.format(template);
			if (out != null && !out.isBlank()) lines.add(out);
		}
	}

	@Override
	public int width() {
		refresh();
		int w = 20;
		for (String line : lines) w = Math.max(w, Draw.width(line));
		return TextLinesElement.gridded(w + 10);
	}

	@Override
	public int height() {
		refresh();
		return TextLinesElement.gridded(Math.max(1, lines.size()) * LINE_H + 4);
	}

	@Override
	public void render(Ui ui, int x, int y, boolean preview) {
		Theme t = ui.theme;
		int w = width(), h = height();
		if (hud().backgrounds.get()) Draw.hudBackground(ui.g, x, y, w, h, hud().backgroundColor.argb());
		int ly = y + (h - lines.size() * LINE_H) / 2;
		for (String line : lines) {
			Draw.text(ui.g, line, x + 5, ly + 1, t.text);
			ly += LINE_H;
		}
	}

	@Override
	protected int[] defaultPosition(int screenW, int screenH) {
		return new int[] {2, TextLinesElement.stackY(3)};
	}
}
