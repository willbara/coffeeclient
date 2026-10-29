package dev.coffeeclient.gui.widget;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleProfiler;

import java.util.List;
import java.util.Locale;

/**
 * Enabled modules' cost in milliseconds per second (tick, render, events, total), most expensive
 * first. Click a column header to sort by it. Profiling runs only while this table is drawn.
 */
public class ModulePerfTable extends Widget {
	public static final int ROW = 10;
	private static final String[] HEADERS = {"Tick", "Render", "Events", "Total"};
	private static final ModuleProfiler.Phase[] SORTS = {ModuleProfiler.Phase.TICK, ModuleProfiler.Phase.RENDER, ModuleProfiler.Phase.EVENT, null};
	private static final int COL_W = 42;

	/** Index into {@link #HEADERS}; kept across openings. */
	private static int sortColumn = 3;

	public ModulePerfTable() {
		tooltip("Milliseconds per second each enabled module spends", "Click a column to sort by it");
	}

	@Override
	protected void draw(Ui ui) {
		List<Module> all = CoffeeClient.modules().all();
		ModuleProfiler.keepAlive();
		ModuleProfiler.roll(all);
		Theme t = ui.theme;

		int right = x + width;
		Draw.text(ui.g, "Module", x, y, t.dimText);
		for (int c = 0; c < HEADERS.length; c++) {
			int colRight = right - (HEADERS.length - 1 - c) * COL_W;
			int color = c == sortColumn ? t.accent : t.dimText;
			Draw.textRight(ui.g, HEADERS[c], colRight, y, color);
		}
		Draw.rect(ui.g, x, y + ROW - 1, width, 1, t.border);

		List<Module> ranked = ModuleProfiler.ranked(all, SORTS[sortColumn]);
		int rows = Math.max(0, (height - ROW) / ROW);
		if (ranked.isEmpty()) {
			Draw.text(ui.g, "Measuring...", x, y + ROW + 2, t.dimText);
			return;
		}
		for (int i = 0; i < Math.min(rows, ranked.size()); i++) {
			Module m = ranked.get(i);
			int ry = y + ROW + 1 + i * ROW;
			Draw.text(ui.g, Draw.trim(m.name(), width - HEADERS.length * COL_W - 4), x, ry, t.text);
			for (int c = 0; c < HEADERS.length; c++) {
				double ms = SORTS[c] == null ? m.profilerStats().totalMs() : m.profilerStats().ms(SORTS[c]);
				int colRight = right - (HEADERS.length - 1 - c) * COL_W;
				Draw.textRight(ui.g, format(ms), colRight, ry, ms >= 5 ? t.warn : c == sortColumn ? t.text : t.dimText);
			}
		}
	}

	private static String format(double ms) {
		if (ms <= 0) return "-";
		return ms < 10 ? String.format(Locale.ROOT, "%.2f", ms) : String.format(Locale.ROOT, "%.0f", ms);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button != LEFT || my > y + ROW) return false;
		int right = x + width;
		for (int c = 0; c < HEADERS.length; c++) {
			int colRight = right - (HEADERS.length - 1 - c) * COL_W;
			if (mx > colRight - COL_W && mx <= colRight) {
				sortColumn = c;
				return true;
			}
		}
		return false;
	}
}
