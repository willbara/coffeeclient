package dev.coffeeclient.gui.screen;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.CoffeeScreen;
import dev.coffeeclient.gui.UiState;
import dev.coffeeclient.gui.widget.Button;
import dev.coffeeclient.gui.widget.CategoryColumn;
import dev.coffeeclient.gui.widget.ModuleRow;
import dev.coffeeclient.gui.widget.TextField;
import dev.coffeeclient.gui.widget.Widget;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.ModuleSearch;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Category columns of module rows. Column positions and collapse state persist for the session. */
public class ClickGuiScreen extends CoffeeScreen {
	private static final int GAP = 6;
	private static final int BOTTOM_BAR = 24;

	private record ColumnState(int x, int y, boolean collapsed) {
	}

	/** Remembered while the game runs; reset with the "Reset layout" button. */
	private static final Map<Category, ColumnState> LAYOUT = new EnumMap<>(Category.class);
	private static int layoutForWidth = -1;

	private TextField search;
	private String hitsFor;
	private Map<Module, ModuleSearch.Hit> hits = Map.of();

	public ClickGuiScreen(@Nullable Screen parent) {
		super("ClickGUI", parent);
	}

	@Override
	protected @Nullable KeyMapping toggleKey() {
		return CoffeeClient.keybinds().clickGui;
	}

	@Override
	protected void build() {
		int width = layoutWidth(), height = layoutHeight();
		if (layoutForWidth != width) {
			LAYOUT.clear();
			layoutForWidth = width;
		}
		Category[] categories = Category.values();
		int colW = Math.max(84, Math.min(118, (width - 12 - GAP * (categories.length - 1)) / categories.length));
		int perRow = Math.max(1, Math.min(categories.length, (width - 12 + GAP) / (colW + GAP)));
		// when wrapping, widen columns to use the freed space
		colW = Math.max(colW, Math.min(118, (width - 12 - GAP * (perRow - 1)) / perRow));
		int rows = (categories.length + perRow - 1) / perRow;
		int rowStep = rows > 1 ? (height - BOTTOM_BAR - 8) / rows : 0;
		int totalW = perRow * colW + (perRow - 1) * GAP;
		int startX = (width - totalW) / 2;

		search = new TextField().placeholder("Search modules...").maxLength(32);
		ModuleRow.matchReason = this::matchReason;
		for (int i = 0; i < categories.length; i++) {
			Category category = categories[i];
			CategoryColumn column = new CategoryColumn(category, CoffeeClient.modules().sortedFor(category), i)
				.filter(this::matches)
				.maxBottom(height - BOTTOM_BAR);
			ColumnState state = LAYOUT.get(category);
			// wrapped rows: stop above the next row until the user moves the column
			if (state == null && i / perRow < rows - 1) column.maxBottom(8 + (i / perRow + 1) * rowStep - 4);
			int cx = state != null ? state.x() : startX + (i % perRow) * (colW + GAP);
			int cy = state != null ? state.y() : 8 + (i / perRow) * rowStep;
			column.setBounds(cx, cy, colW, CategoryColumn.HEADER_H);
			column.clampTo(width, height - BOTTOM_BAR);
			if (state != null) column.setCollapsed(state.collapsed());
			column.onMoved(() -> {
				column.clampTo(width, height - BOTTOM_BAR);
				column.maxBottom(height - BOTTOM_BAR);
				LAYOUT.put(category, new ColumnState(column.x, column.y, column.collapsed()));
			});
			root.add(column);
		}

		int barY = height - BOTTOM_BAR + 5;
		root.add(search).setBounds(width / 2 - 80, barY, 160, 14);
		root.add(new Button("Reset layout", () -> {
			LAYOUT.clear();
			rebuildWidgets();
		})).setBounds(width - 78, barY, 72, 14);
	}

	private boolean matches(Module m) {
		String q = search == null ? "" : search.text().trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) return true;
		return hits(q).containsKey(m);
	}

	/** Scored matches for {@code q}, rebuilt only when the query changes; typos count only when nothing else matched. */
	private Map<Module, ModuleSearch.Hit> hits(String q) {
		if (q.equals(hitsFor)) return hits;
		Map<Module, ModuleSearch.Hit> all = new HashMap<>();
		boolean real = false;
		for (Module m : CoffeeClient.modules().all()) {
			List<String> settings = new ArrayList<>();
			for (Setting<?> s : m.settings()) settings.add(s.name());
			ModuleSearch.Hit hit = ModuleSearch.score(q, m.name(), m.aliases(), m.description(), settings);
			if (hit == null) continue;
			all.put(m, hit);
			real |= hit.score() < ModuleSearch.TYPO;
		}
		if (real) all.values().removeIf(h -> h.score() >= ModuleSearch.TYPO);
		hitsFor = q;
		hits = all;
		return all;
	}

	/** Why {@code m} is listed for the current search, for its tooltip; null when its name matched. */
	public String matchReason(Module m) {
		String q = search == null ? "" : search.text().trim().toLowerCase(Locale.ROOT);
		ModuleSearch.Hit hit = q.isEmpty() ? null : hits(q).get(m);
		return hit == null ? null : hit.reason();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		boolean handled = super.mouseClicked(event, doubleClick);
		// bring a grabbed column to the front
		List<Widget> children = root.children();
		for (int i = 0; i < children.size(); i++) {
			if (children.get(i) instanceof CategoryColumn c && c.isDragging()) {
				children.remove(i);
				int insertAt = (int) children.stream().filter(w -> w instanceof CategoryColumn).count();
				children.add(insertAt, c);
				break;
			}
		}
		return handled;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		// type anywhere to search
		if (UiState.focused() == null && event.isAllowedChatCharacter() && !Character.isWhitespace(event.codepoint())) search.focus();
		if (UiState.focused() == search) {
			search.charTyped(event.codepointAsString());
			return true;
		}
		return super.charTyped(event);
	}
}
