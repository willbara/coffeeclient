package dev.coffeeclient.gui.screen;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.gui.widget.CycleButton;
import dev.coffeeclient.gui.widget.Label;
import dev.coffeeclient.gui.widget.Panel;
import dev.coffeeclient.gui.widget.ScrollList;
import dev.coffeeclient.gui.widget.Widget;
import dev.coffeeclient.module.modules.world.StashFinder;
import dev.coffeeclient.module.modules.world.stashfinder.HistoryRow;
import dev.coffeeclient.module.modules.world.stashfinder.StashHistory;
import dev.coffeeclient.util.Coords;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * StashFinder's remembered stashes and detector finds, shown beside its settings. Rows can be
 * filtered, sorted, marked new/visited/cleared, copied, or focused as a tracer.
 */
public final class StashHistoryPanel extends Panel {
	private static final int ROW_H = 30;
	private static final int ICON = 10;

	private enum TypeFilter {
		ALL("All types"), STASH("Stashes"), FIND("Finds");

		final String label;

		TypeFilter(String label) {
			this.label = label;
		}
	}

	private enum StateFilter {
		ALL("Any state", null), NEW("New", StashHistory.State.NEW),
		VISITED("Visited", StashHistory.State.VISITED), CLEARED("Cleared", StashHistory.State.CLEARED);

		final String label;
		final StashHistory.State state;

		StateFilter(String label, StashHistory.State state) {
			this.label = label;
			this.state = state;
		}
	}

	private enum DimFilter {
		ALL("All dimensions"), CURRENT("This dimension");

		final String label;

		DimFilter(String label) {
			this.label = label;
		}
	}

	private enum Sort {
		NEWEST("Newest"), OLDEST("Oldest"), NEAREST("Nearest"), COUNT("Most finds");

		final String label;

		Sort(String label) {
			this.label = label;
		}
	}

	private final StashFinder module;
	private final ScrollList list = new ScrollList().padding(1);
	private final CycleButton typeButton;
	private final CycleButton stateButton;
	private final CycleButton dimensionButton;
	private final CycleButton sortButton;
	private TypeFilter typeFilter = TypeFilter.ALL;
	private StateFilter stateFilter = StateFilter.ALL;
	private DimFilter dimFilter = DimFilter.ALL;
	private Sort sort = Sort.NEWEST;
	private long seenVersion = -1;
	private int nearX = Integer.MIN_VALUE, nearZ = Integer.MIN_VALUE;

	public StashHistoryPanel(StashFinder module) {
		super("History");
		this.module = module;
		subtitle(() -> module.historyCount() + " entries");
		add(typeButton = new CycleButton(() -> "Type: " + typeFilter.label,
			dir -> {
				typeFilter = next(TypeFilter.values(), typeFilter, dir);
				rebuild();
			}));
		add(stateButton = new CycleButton(() -> "State: " + stateFilter.label,
			dir -> {
				stateFilter = next(StateFilter.values(), stateFilter, dir);
				rebuild();
			}));
		add(dimensionButton = new CycleButton(() -> "Dim: " + dimFilter.label,
			dir -> {
				dimFilter = next(DimFilter.values(), dimFilter, dir);
				rebuild();
			}));
		add(sortButton = new CycleButton(() -> "Sort: " + sort.label,
			dir -> {
				sort = next(Sort.values(), sort, dir);
				rebuild();
			}));
		add(list);
	}

	private static <T extends Enum<T>> T next(T[] values, T current, int dir) {
		return values[Math.floorMod(current.ordinal() + dir, values.length)];
	}

	@Override
	protected void draw(Ui ui) {
		int x = contentX(), y = contentY(), w = contentWidth();
		int half = (w - 4) / 2;
		typeButton.setBounds(x, y, half, 14);
		stateButton.setBounds(x + half + 4, y, half, 14);
		dimensionButton.setBounds(x, y + 18, half, 14);
		sortButton.setBounds(x + half + 4, y + 18, half, 14);
		int listY = y + 38;
		list.setBounds(x, listY, w, Math.max(0, contentY() + contentHeight() - listY));
		if (module.historyVersion() != seenVersion || (sort == Sort.NEAREST && playerMoved())) rebuild();
		super.draw(ui);
	}

	/** The nearest-first order is only right until the player moves a block. */
	private boolean playerMoved() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && (mc.player.getBlockX() != nearX || mc.player.getBlockZ() != nearZ);
	}

	private void rebuild() {
		seenVersion = module.historyVersion();
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			nearX = mc.player.getBlockX();
			nearZ = mc.player.getBlockZ();
		}
		list.clear();
		String current = currentDimension();
		List<HistoryRow> rows = new ArrayList<>();
		for (HistoryRow row : module.historyRows()) if (matches(row, current)) rows.add(row);
		rows.sort(comparator());
		for (HistoryRow row : rows) list.add(new Row(row));
		if (rows.isEmpty()) {
			boolean any = module.historyCount() > 0;
			list.add(new Label(any ? "No entries match these filters." : "Nothing remembered yet.", Label.Style.DIM)
				.indent(6).setBounds(0, 0, 0, 14));
			if (!any) list.add(new Label("Stashes and detector finds show up here.", Label.Style.DIM).indent(6).setBounds(0, 0, 0, 12));
		}
	}

	private boolean matches(HistoryRow row, String current) {
		if (typeFilter == TypeFilter.STASH && row.kind() != null) return false;
		if (typeFilter == TypeFilter.FIND && row.kind() == null) return false;
		if (stateFilter.state != null && row.state() != stateFilter.state) return false;
		return dimFilter != DimFilter.CURRENT || current == null || current.equals(row.dimension());
	}

	private Comparator<HistoryRow> comparator() {
		Comparator<HistoryRow> newest = Comparator.comparingLong(HistoryRow::lastSeen).reversed();
		return switch (sort) {
			case NEWEST -> newest.thenComparing(HistoryRow::key);
			case OLDEST -> Comparator.comparingLong(HistoryRow::firstSeen).thenComparing(HistoryRow::key);
			case NEAREST -> Comparator.comparingDouble(this::distanceSq).thenComparing(newest);
			case COUNT -> Comparator.comparingInt(HistoryRow::count).reversed().thenComparing(newest);
		};
	}

	private double distanceSq(HistoryRow row) {
		Minecraft mc = Minecraft.getInstance();
		String current = currentDimension();
		if (mc.player == null || (current != null && !current.equals(row.dimension()))) return Double.MAX_VALUE;
		double dx = row.x() - mc.player.getX();
		double dz = row.z() - mc.player.getZ();
		return dx * dx + dz * dz;
	}

	private static String currentDimension() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? null : mc.level.dimension().identifier().toString();
	}

	private String detail(HistoryRow row) {
		String current = currentDimension();
		String where = current == null || current.equals(row.dimension()) ? "" : dimName(row.dimension()) + " · ";
		return where + row.detail();
	}

	private static String dimName(String id) {
		int colon = id.indexOf(':');
		String path = colon >= 0 ? id.substring(colon + 1) : id;
		return switch (path) {
			case "overworld" -> "Overworld";
			case "the_nether" -> "Nether";
			case "the_end" -> "End";
			default -> path;
		};
	}

	private static String stateName(StashHistory.State state) {
		return switch (state) {
			case NEW -> "New";
			case VISITED -> "Visited";
			case CLEARED -> "Cleared";
		};
	}

	private static String ago(long time) {
		if (time <= 0) return "?";
		long seconds = Math.max(0, (System.currentTimeMillis() - time) / 1000);
		if (seconds < 60) return "now";
		if (seconds < 3600) return (seconds / 60) + "m";
		if (seconds < 86400) return (seconds / 3600) + "h";
		return (seconds / 86400) + "d";
	}

	private static String when(long time) {
		if (time <= 0) return "unknown";
		return String.format(Locale.ROOT, "%tF %tR", time, time);
	}

	private static void crosshair(Ui ui, int x, int y, int color) {
		Draw.rect(ui.g, x + 2, y, 3, 1, color);
		Draw.rect(ui.g, x + 2, y + 6, 3, 1, color);
		Draw.rect(ui.g, x, y + 2, 1, 3, color);
		Draw.rect(ui.g, x + 6, y + 2, 1, 3, color);
		Draw.rect(ui.g, x + 3, y + 3, 1, 1, color);
	}

	private final class Row extends Widget {
		private final HistoryRow entry;
		private long copiedAt;

		Row(HistoryRow entry) {
			this.entry = entry;
			this.height = ROW_H;
			tooltip(() -> {
				List<String> lines = new ArrayList<>(4);
				lines.add("First seen: " + when(entry.firstSeen()));
				lines.add("Last seen: " + when(entry.lastSeen()));
				if (entry.kind() == null) lines.add("Y: " + entry.y());
				lines.add(entry.detail());
				return lines;
			});
		}

		@Override
		protected void draw(Ui ui) {
			Theme t = ui.theme;
			boolean focused = module.isFocused(entry.key());
			Draw.listHighlight(ui.g, t, x, y, width, height, focused, hover, 0x14);

			int stateColor = switch (entry.state()) {
				case NEW -> t.accent;
				case VISITED -> t.warn;
				case CLEARED -> t.dimText;
			};
			Draw.rect(ui.g, x + 2, y + 4, 2, height - 8, stateColor);

			int iconX = x + width - ICON - 4;
			int textRight = iconX - 4;
			int color = entry.kind() == null ? 0xFF000000 | module.tracerColor.argb() : 0xFF000000 | module.colorOf(entry.kind());
			String title = entry.kind() == null ? "Stash" : entry.kind().label;
			Draw.text(ui.g, Draw.trim(title, textRight - x - 8), x + 8, y + 3, color);
			String coords = entry.kind() == null ? Coords.xz(entry.x(), entry.z()) : Coords.xyz(entry.x(), entry.y(), entry.z());
			Draw.textRight(ui.g, Draw.trim(coords, Math.max(0, textRight - x - 8 - Draw.width(title) - 6)), textRight, y + 3, t.text);
			String sub = stateName(entry.state()) + " · " + detail(entry);
			String age = ago(entry.lastSeen());
			Draw.text(ui.g, Draw.trim(sub, Math.max(0, textRight - x - 8 - Draw.width(age) - 6)), x + 8, y + 14, t.dimText);
			Draw.textRight(ui.g, age, textRight, y + 14, t.dimText);

			boolean overState = ui.hoverAllowed && ui.mouseX >= x && ui.mouseX < x + 9 && ui.mouseY >= y && ui.mouseY < y + height;
			if (overState) ui.tooltip("State: " + stateName(entry.state()) + " - click to change");

			int copyY = y + 4;
			int focusY = y + 16;
			boolean overCopy = ui.hoverAllowed && ui.mouseX >= iconX && ui.mouseX < iconX + ICON
				&& ui.mouseY >= copyY && ui.mouseY < copyY + ICON;
			boolean overFocus = ui.hoverAllowed && ui.mouseX >= iconX && ui.mouseX < iconX + ICON
				&& ui.mouseY >= focusY && ui.mouseY < focusY + ICON;
			boolean recently = System.currentTimeMillis() - copiedAt < 1200;
			int copyColor = recently ? t.accent : overCopy ? t.strongText() : t.dimText;
			if (recently) Draw.check(ui.g, iconX, copyY + 1, copyColor);
			else Draw.copyIcon(ui.g, iconX, copyY, copyColor);
			if (overCopy) ui.tooltip(recently ? "Copied!" : "Copy coordinates");
			crosshair(ui, iconX, focusY, focused ? t.accent : overFocus ? t.strongText() : t.dimText);
			if (overFocus) ui.tooltip(focused ? "Stop focusing this tracer" : "Focus tracer");
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			if (button != LEFT) return false;
			if (mx < x + 9) {
				module.cycleState(entry.key());
				return true;
			}
			int iconX = x + width - ICON - 4;
			if (mx >= iconX && mx < iconX + ICON) {
				if (my >= y + 4 && my < y + 4 + ICON) {
					String text = entry.kind() == null ? Coords.xz(entry.x(), entry.z()) : Coords.xyz(entry.x(), entry.y(), entry.z());
					Minecraft.getInstance().keyboardHandler.setClipboard(text);
					copiedAt = System.currentTimeMillis();
					return true;
				}
				if (my >= y + 16 && my < y + 16 + ICON) {
					module.focus(entry.key());
					return true;
				}
			}
			return true;
		}
	}
}
