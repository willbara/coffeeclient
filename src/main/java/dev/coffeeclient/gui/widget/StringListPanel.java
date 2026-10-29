package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.setting.StringListSetting;

import java.util.List;

/**
 * Editor for a {@link StringListSetting}: a field to add entries and a scrolling list of the current
 * entries, each editable inline or removable with its ✕. Used embedded in module windows and as the
 * body of the full-screen editor.
 */
public class StringListPanel extends Container {
	private static final int TOOLBAR = 18;
	private static final int FOOTER = 12;

	private final StringListSetting setting;
	private final TextField entry;
	private final Button add;
	private final ScrollList list;
	private String lastSignature = null;

	public StringListPanel(StringListSetting setting) {
		this.setting = setting;
		this.entry = add(new TextField().maxLength(setting.maxLength()).onSubmit(s -> submit()));
		this.add = add(new Button("Add", this::submit));
		this.list = add(new ScrollList().padding(1));
	}

	public TextField entryField() {
		return entry;
	}

	private void submit() {
		setting.add(entry.text());
		entry.text("");
		entry.focus();
	}

	@Override
	public Widget setBounds(int x, int y, int width, int height) {
		super.setBounds(x, y, width, height);
		entry.setBounds(x, y, width - 44, 14);
		entry.placeholder(setting.maxEntries() > 0 ? "Add entry (" + setting.get().size() + "/" + setting.maxEntries() + ")..." : "Add entry...");
		add.setBounds(x + width - 40, y, 40, 14);
		list.setBounds(x + 1, y + TOOLBAR + 1, width - 2, height - TOOLBAR - FOOTER - 2);
		if (lastSignature == null) rebuild();
		return this;
	}

	private void rebuild() {
		List<String> entries = setting.get();
		lastSignature = String.join("\u0000", entries);
		list.clear();
		list.scrollToTop();
		for (String value : entries) list.add(new Entry(value));
		if (entries.isEmpty()) list.add(new Label("No entries yet. Type above and press Add.", Label.Style.DIM).indent(6)).setBounds(0, 0, 0, 14);
	}

	@Override
	protected void draw(Ui ui) {
		if (!String.join("\u0000", setting.get()).equals(lastSignature)) rebuild();
		Draw.panel(ui.g, ui.theme, list.x - 1, list.y - 1, list.width + 2, list.height + 2);
		drawChildren(ui);
	}

	private final class Entry extends Container implements ScrollList.Relayout {
		private final String original;
		private final TextField field;
		private final Button remove;

		Entry(String value) {
			this.original = value;
			this.height = 16;
			this.field = add(new TextField().text(value).maxLength(setting.maxLength()));
			this.field.onChange(s -> {
				if (!s.isBlank()) setting.replace(original, s);
			});
			this.remove = add(new Button("✕", () -> setting.remove(original)));
		}

		@Override
		public void relayout() {
			field.setBounds(x + 3, y + 1, Math.max(20, width - 24), 14);
			remove.setBounds(x + width - 19, y + 1, 18, 14);
		}

		@Override
		protected void draw(Ui ui) {
			Theme t = ui.theme;
			if (hover > 0.01f) Draw.rect(ui.g, x, y, width, height, t.accent((int) (0x12 * hover)));
			relayout();
			drawChildren(ui);
		}
	}
}
