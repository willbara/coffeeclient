package dev.coffeeclient.gui.screen;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.CoffeeScreen;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.gui.widget.Widget;
import dev.coffeeclient.util.History;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Client notifications since startup, newest first, with the time each was sent. Kept in memory only.
 */
public class HistoryScreen extends CoffeeScreen {
	public HistoryScreen(@Nullable Screen parent) {
		super("History", parent);
	}

	@Override
	protected @Nullable KeyMapping toggleKey() {
		return CoffeeClient.keybinds().history;
	}

	@Override
	protected void build() {
		int[] frame = centred43(680);
		int x0 = frame[0] + 4, y0 = frame[1], contentW = frame[2] - 8;
		int top = addHeader(x0, y0 + 4, contentW, "History");
		root.add(new LineList()).setBounds(x0, top, contentW, y0 + frame[3] - top - 4);
	}

	/** Draws only the rows on screen, so a full history costs no more to draw than a short one. */
	private static final class LineList extends Widget {
		private static final int ROW_H = 12;
		private static final int WHEEL_ROWS = 3;

		private List<History.Entry> lines = List.of();
		private int seenVersion = -1;
		private int first;

		@Override
		protected void draw(Ui ui) {
			Theme t = ui.theme;
			if (History.version() != seenVersion) {
				seenVersion = History.version();
				lines = History.newestFirst();
			}
			Draw.panel(ui.g, t, x, y, width, height);
			if (lines.isEmpty()) {
				Draw.textCentered(ui.g, "Nothing yet. Module messages and Notifier reports show up here.", x + width / 2, y + height / 2 - 4, t.dimText);
				return;
			}
			int rows = Math.max(1, (height - 4) / ROW_H);
			first = Math.max(0, Math.min(first, lines.size() - rows));
			for (int i = 0; i < rows && first + i < lines.size(); i++) {
				History.Entry e = lines.get(first + i);
				int ry = y + 4 + i * ROW_H;
				int tx = x + 6;
				String time = String.format(Locale.ROOT, "%tT", e.at());
				Draw.text(ui.g, time, tx, ry, t.dimText);
				tx += Draw.width(time) + 6;
				String tag = "[Coffee] ";
				Draw.text(ui.g, tag, tx, ry, t.lightAccent());
				tx += Draw.width(tag);
				String source = e.source() + " ";
				Draw.text(ui.g, source, tx, ry, 0xFFFFFFFF);
				tx += Draw.width(source);
				Draw.text(ui.g, Draw.trim(e.text(), x + width - tx - 6), tx, ry, t.text);
			}
		}

		@Override
		public boolean mouseScrolled(double mx, double my, double amount) {
			if (!contains(mx, my)) return false;
			first = Math.max(0, first - (int) Math.signum(amount) * WHEEL_ROWS);
			return true;
		}
	}
}
