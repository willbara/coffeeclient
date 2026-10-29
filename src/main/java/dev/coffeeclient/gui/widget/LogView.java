package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.LogTail;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Virtualised log tail: only visible lines are drawn. Follows new output while scrolled to the bottom;
 * scrolling up pauses following. Shows the lines that pass the current {@link Filter}.
 *
 * <p>Clicking a line copies it, together with its stack trace.
 */
public class LogView extends Widget {
	private static final int ROW_H = 10;
	private static final int BAR_W = 3;
	private static final long FLASH_MS = 700;

	/** Which levels to show. */
	public enum Level { ALL, ERRORS, WARNINGS, INFO }

	/** What the view shows; kept across dashboard openings. */
	public static final class Filter {
		public Level level = Level.ALL;
		public boolean hideNoise;
		public String search = "";
		private int revision;

		public void changed() {
			revision++;
		}

		boolean accepts(LogTail.Line line, String query) {
			if (hideNoise && line.noise()) return false;
			boolean levelOk = switch (level) {
				case ALL -> true;
				case ERRORS -> line.level() == LogTail.Level.ERROR;
				case WARNINGS -> line.level() == LogTail.Level.WARN;
				case INFO -> line.level() == LogTail.Level.INFO;
			};
			return levelOk && (query.isEmpty() || line.text().toLowerCase(Locale.ROOT).contains(query));
		}
	}

	public static final Filter FILTER = new Filter();

	/** All buffered lines, and the indexes of those that pass the filter. */
	private List<LogTail.Line> all = List.of();
	private int[] shown = new int[0];
	private int seenVersion = -1, seenFilter = -1;
	private long lastRefresh;
	private double scroll;
	private boolean follow = true;
	private boolean draggingBar;
	private int flashIndex = -1;
	private long flashAt;
	private String flashText = "";

	private int visibleRows() {
		return Math.max(1, (height - 2) / ROW_H);
	}

	private int maxScroll() {
		return Math.max(0, shown.length - visibleRows());
	}

	private void refresh(long now) {
		boolean filterChanged = FILTER.revision != seenFilter;
		if (!filterChanged && now - lastRefresh < 250) return;
		lastRefresh = now;
		int v = LogTail.version();
		if (v == seenVersion && !filterChanged) return;
		seenVersion = v;
		seenFilter = FILTER.revision;
		all = LogTail.snapshot();
		String query = FILTER.search.trim().toLowerCase(Locale.ROOT);
		int[] out = new int[all.size()];
		int n = 0;
		// A stack trace follows its line: shown when the line is, hidden when it is not.
		boolean parentShown = false;
		for (int i = 0; i < all.size(); i++) {
			LogTail.Line line = all.get(i);
			boolean show = line.continuation() ? parentShown : FILTER.accepts(line, query);
			if (!line.continuation()) parentShown = show;
			if (show) out[n++] = i;
		}
		shown = java.util.Arrays.copyOf(out, n);
		if (filterChanged) follow = true;
	}

	@Override
	protected void draw(Ui ui) {
		refresh(ui.timeMs);
		Theme t = ui.theme;
		if (follow) scroll = maxScroll();
		scroll = Math.max(0, Math.min(maxScroll(), scroll));
		int first = (int) scroll;
		int rows = visibleRows();
		int textW = width - BAR_W - 8;
		ui.g.enableScissor(x, y, x + width, y + height);
		if (shown.length == 0) Draw.text(ui.g, "No lines match the filter", x + 4, y + 2, t.dimText);
		for (int i = 0; i < rows && first + i < shown.length; i++) {
			int index = shown[first + i];
			LogTail.Line line = all.get(index);
			int ly = y + 1 + i * ROW_H;
			boolean over = ui.hoverAllowed && ui.mouseX >= x && ui.mouseX < x + width - BAR_W - 2 && ui.mouseY >= ly && ui.mouseY < ly + ROW_H;
			int color = switch (line.level()) {
				case ERROR -> t.error;
				case WARN -> t.warn;
				default -> Colors.lerp(t.dimText, t.text, 0.35f);
			};
			if (line.noise()) color = Colors.lerp(color, t.dimText, 0.6f);
			if (line.level() != LogTail.Level.INFO) Draw.rect(ui.g, x, ly, 1, ROW_H, Colors.withAlpha(color, 0xB0));
			long sinceFlash = ui.timeMs - flashAt;
			if (index == flashIndex && sinceFlash < FLASH_MS) {
				Draw.rect(ui.g, x, ly, width - BAR_W - 2, ROW_H, t.accent((int) (0x50 * (1 - sinceFlash / (float) FLASH_MS))));
			} else if (over) {
				Draw.rect(ui.g, x, ly, width - BAR_W - 2, ROW_H, t.accent(0x1C));
			}
			String text = Draw.trim(line.text(), textW);
			Draw.text(ui.g, text, x + 4, ly + 1, color);
			if (over) {
				List<String> tip = new ArrayList<>();
				if (!text.equals(line.text())) tip.addAll(wrap(line.text(), 300));
				tip.add(ui.timeMs - flashAt < FLASH_MS ? flashText : "Click: copy line");
				ui.tooltip(tip);
			}
		}
		ui.g.disableScissor();
		drawScrollbar(ui);
	}

	private static List<String> wrap(String s, int width) {
		List<String> out = new ArrayList<>();
		String rest = s;
		while (!rest.isEmpty() && out.size() < 12) {
			String part = Draw.substringByWidth(rest, width);
			if (part.isEmpty()) break;
			out.add(part);
			rest = rest.substring(part.length());
		}
		return out;
	}

	private void drawScrollbar(Ui ui) {
		Theme t = ui.theme;
		int bx = x + width - BAR_W;
		int max = maxScroll();
		if (t.glass()) {
			if (max <= 0) return;
			int thumbH = Math.max(10, height * visibleRows() / Math.max(1, shown.length));
			int thumbY = y + (int) ((height - thumbH) * (scroll / max));
			dev.coffeeclient.gui.fx.Shapes.roundRect(ui.g, bx, thumbY + 1, BAR_W, thumbH - 2, 1, follow ? t.accent : Colors.withAlpha(0xFFFFFF, 0x50));
			return;
		}
		Draw.rect(ui.g, bx, y, BAR_W, height, Colors.withAlpha(t.control, 0xA0));
		if (max <= 0) return;
		int thumbH = Math.max(10, height * visibleRows() / Math.max(1, shown.length));
		int thumbY = y + (int) ((height - thumbH) * (scroll / max));
		Draw.rect(ui.g, bx, thumbY, BAR_W, thumbH, follow ? t.accent : Colors.lerp(t.border, t.accent, 0.6f));
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		scroll = Math.max(0, Math.min(maxScroll(), scroll - amount * 3));
		follow = scroll >= maxScroll();
		return true;
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (mx >= x + width - BAR_W - 2) {
			draggingBar = true;
			dragTo(my);
			return true;
		}
		int row = (int) scroll + (int) ((my - y - 1) / ROW_H);
		if (button != LEFT || row < 0 || row >= shown.length) return true;
		// Copy the whole entry: back up to its first line, then take its stack trace with it.
		int index = shown[row];
		while (index > 0 && all.get(index).continuation()) index--;
		StringBuilder sb = new StringBuilder(all.get(index).text().strip());
		int lines = 1;
		for (int i = index + 1; i < all.size() && all.get(i).continuation(); i++, lines++) sb.append('\n').append(all.get(i).text().strip());
		copy(sb.toString(), shown[row], lines > 1 ? "Copied line and " + (lines - 1) + " trace lines" : "Copied");
		return true;
	}

	private void copy(String text, int index, String message) {
		Minecraft.getInstance().keyboardHandler.setClipboard(text);
		flashIndex = index;
		flashAt = System.currentTimeMillis();
		flashText = message;
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (!draggingBar) return false;
		dragTo(my);
		return true;
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		boolean was = draggingBar;
		draggingBar = false;
		return was;
	}

	private void dragTo(double my) {
		double f = (my - y) / Math.max(1, height);
		scroll = Math.max(0, Math.min(maxScroll(), f * maxScroll()));
		follow = scroll >= maxScroll();
	}
}
