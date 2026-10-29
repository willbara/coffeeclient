package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.LogTail;

import java.util.function.IntSupplier;

/**
 * The log's filter chips: All / Errors / Warnings / Info pick the level shown (each with its count),
 * and Noise hides or shows known harmless chatter. Edits {@link LogView#FILTER}.
 */
public class LogFilterBar extends Widget {
	public static final int HEIGHT = 12;
	private static final int GAP = 3;

	private record Chip(String label, IntSupplier count, LogView.Level level) {
	}

	private static final Chip[] CHIPS = {
		new Chip("All", () -> (int) LogTail.total(), LogView.Level.ALL),
		new Chip("Errors", () -> (int) LogTail.errors(), LogView.Level.ERRORS),
		new Chip("Warnings", () -> (int) LogTail.warnings(), LogView.Level.WARNINGS),
		new Chip("Info", () -> -1, LogView.Level.INFO),
	};

	public LogFilterBar() {
		tooltip("Filter the log by level", "Noise: known harmless messages (unknown tab entries, offline auth, ...)");
	}

	private static String text(Chip chip) {
		int n = chip.count().getAsInt();
		return n < 0 ? chip.label() : chip.label() + " " + n;
	}

	private static String noiseText() {
		return (LogView.FILTER.hideNoise ? "Noise hidden " : "Noise ") + LogTail.noise();
	}

	@Override
	protected void draw(Ui ui) {
		Theme t = ui.theme;
		int cx = x;
		for (Chip chip : CHIPS) {
			String label = text(chip);
			int w = Draw.width(label) + 8;
			boolean active = LogView.FILTER.level == chip.level();
			int color = switch (chip.level()) {
				case ERRORS -> t.error;
				case WARNINGS -> t.warn;
				default -> t.text;
			};
			chip(ui, cx, w, label, active, color);
			cx += w + GAP;
		}
		String noise = noiseText();
		int w = Draw.width(noise) + 8;
		chip(ui, x + width - w, w, noise, LogView.FILTER.hideNoise, t.dimText);
	}

	private void chip(Ui ui, int cx, int w, String label, boolean active, int color) {
		Theme t = ui.theme;
		boolean over = ui.hoverAllowed && ui.mouseX >= cx && ui.mouseX < cx + w && ui.mouseY >= y && ui.mouseY < y + height;
		int fill = active ? t.accent(0x50) : over ? t.accent(0x20) : Colors.withAlpha(t.control, 0x90);
		Draw.box(ui.g, cx, y, w, height, fill, active ? t.accent : t.border);
		Draw.text(ui.g, label, cx + 4, y + (height - 8) / 2, active ? t.strongText() : color);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button != LEFT) return false;
		int cx = x;
		for (Chip chip : CHIPS) {
			int w = Draw.width(text(chip)) + 8;
			if (mx >= cx && mx < cx + w) {
				LogView.FILTER.level = chip.level();
				LogView.FILTER.changed();
				return true;
			}
			cx += w + GAP;
		}
		int w = Draw.width(noiseText()) + 8;
		if (mx >= x + width - w) {
			LogView.FILTER.hideNoise = !LogView.FILTER.hideNoise;
			LogView.FILTER.changed();
			return true;
		}
		return false;
	}
}
