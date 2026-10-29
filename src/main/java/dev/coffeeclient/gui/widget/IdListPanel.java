package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.util.Colors;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * Searchable checklist for an {@link IdListSetting}: search box, select-all / clear (multi mode), and a
 * scrolling list with optional item icons and preview buttons. Used embedded in module windows and as
 * the body of the full-screen picker.
 */
public class IdListPanel extends Container {
	private static final int TOOLBAR = 18;
	/** The row under the search box that holds the preset dropdown. */
	private static final int PRESET_ROW = 16;
	private static final int FOOTER = 12;

	private final IdListSetting setting;
	private final TextField search;
	private final Button all;
	private final Button none;
	private final Dropdown<String> presets;
	private final ScrollList list;
	private List<String> options = List.of();
	private String lastQuery = null;

	public IdListPanel(IdListSetting setting) {
		this.setting = setting;
		this.search = add(new TextField().maxLength(40).onChange(s -> rebuild()));
		this.all = add(new Button("All", () -> setting.setAll(visible())));
		this.none = add(new Button("None", () -> setting.setAll(List.of())));
		this.presets = add(new Dropdown<>(() -> List.copyOf(setting.presets().keySet()), () -> "Add preset", n -> n, setting::addPreset));
		this.list = add(new ScrollList().padding(1));
		all.visible = none.visible = !setting.isSingle();
		presets.visible = !setting.presets().isEmpty();
	}

	public TextField searchField() {
		return search;
	}

	@Override
	public Widget setBounds(int x, int y, int width, int height) {
		super.setBounds(x, y, width, height);
		boolean multi = !setting.isSingle();
		int buttons = multi ? 2 * 40 + 4 : 0;
		int toolbar = TOOLBAR + (presets.visible ? PRESET_ROW : 0);
		search.setBounds(x, y, width - buttons - (multi ? 4 : 0), 14);
		presets.setBounds(x, y + TOOLBAR, width, 14);
		all.setBounds(x + width - buttons, y, 40, 14);
		none.setBounds(x + width - 40, y, 40, 14);
		list.setBounds(x + 1, y + toolbar + 1, width - 2, height - toolbar - FOOTER - 2);
		if (options.isEmpty()) options = setting.options();
		search.placeholder("Search " + options.size() + " entries...");
		if (lastQuery == null) rebuild();
		return this;
	}

	private List<String> visible() {
		String q = search.text().trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) return options;
		return options.stream().filter(id -> id.toLowerCase(Locale.ROOT).contains(q) || setting.label(id).toLowerCase(Locale.ROOT).contains(q)).toList();
	}

	private void rebuild() {
		lastQuery = search.text();
		list.clear();
		list.scrollToTop();
		List<String> shown = visible();
		for (String id : shown) list.add(new Entry(id));
		if (shown.isEmpty()) list.add(new Label(options.isEmpty() ? "Nothing to choose from yet." : "No matches.", Label.Style.DIM).indent(6)).setBounds(0, 0, 0, 14);
	}

	@Override
	protected void draw(Ui ui) {
		Draw.panel(ui.g, ui.theme, list.x - 1, list.y - 1, list.width + 2, list.height + 2);
		drawChildren(ui);
		String count = setting.isSingle() ? "Selected: " + setting.display() : setting.get().size() + " of " + options.size() + " selected";
		Draw.textRight(ui.g, Draw.trim(count, width - 8), x + width - 4, y + height - FOOTER + 3, ui.theme.dimText);
	}

	private final class Entry extends Widget {
		private final String id;

		Entry(String id) {
			this.id = id;
			this.height = 18;
		}

		@Override
		protected void draw(Ui ui) {
			Theme t = ui.theme;
			boolean on = setting.contains(id);
			if (t.glass()) {
				if (on) dev.coffeeclient.gui.fx.Shapes.roundRect(ui.g, x + 1, y + 1, width - 2, height - 2, 7, t.accent(0x1E));
				CompactSettings.wash(ui, x, y + 1, width, height - 2, hover);
			} else {
				if (on) Draw.rect(ui.g, x, y, width, height, t.accent(0x26));
				if (hover > 0.01f) Draw.rect(ui.g, x, y, width, height, t.accent((int) (0x16 * hover)));
			}
			int bx = x + 5, by = y + 5;
			Draw.box(ui.g, bx, by, 8, 8, t.control, on ? t.accent : t.border);
			if (on) {
				if (setting.isSingle() && t.glass()) dev.coffeeclient.gui.fx.Shapes.disc(ui.g, bx + 4, by + 4, 2, t.accent);
				else if (setting.isSingle()) Draw.rect(ui.g, bx + 2, by + 2, 4, 4, t.accent);
				else Draw.check(ui.g, bx + 1, by + 1, t.accent);
			}
			int tx = x + 18;
			ItemStack icon = setting.icon(id);
			if (icon != null && !icon.isEmpty()) {
				ui.g.item(icon, tx, y + 1);
				tx += 19;
			}
			String label = setting.label(id);
			Draw.text(ui.g, Draw.trim(label, width / 2), tx, y + 5, on ? t.strongText() : t.text);
			int right = x + width - 5;
			if (setting.hasPreview()) {
				boolean over = isHovered(ui) && ui.mouseX >= right - 12;
				Draw.box(ui.g, right - 12, y + 3, 12, 12, over ? t.controlHover : t.control, over ? t.accent : t.border);
				Draw.arrow(ui.g, right - 8, y + 6, 3, 1, over ? t.accent : t.text);
				if (over) ui.tooltip("Preview");
				right -= 18;
			}
			if (!label.equals(id)) Draw.textRight(ui.g, Draw.trim(id, Math.max(0, right - tx - Draw.width(label) - 10)), right, y + 5, Colors.withAlpha(t.dimText, 0xC0));
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			if (setting.hasPreview() && mx >= x + width - 18) {
				setting.runPreview(id);
				return true;
			}
			setting.toggle(id);
			return true;
		}
	}
}
