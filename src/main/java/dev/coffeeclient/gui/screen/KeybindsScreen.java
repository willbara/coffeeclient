package dev.coffeeclient.gui.screen;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.Keybinds;
import dev.coffeeclient.gui.CoffeeScreen;
import dev.coffeeclient.gui.widget.KeybindButton;
import dev.coffeeclient.gui.widget.Label;
import dev.coffeeclient.gui.widget.Panel;
import dev.coffeeclient.gui.widget.ScrollList;
import dev.coffeeclient.gui.widget.TextField;
import dev.coffeeclient.gui.widget.SettingRow;
import dev.coffeeclient.macro.Macro;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.util.KeyNames;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Every key bind in one list: client keys, macros that start on a key, and module binds. Click a key to
 * change it. A bind that shares its key with another says so on its row.
 */
public class KeybindsScreen extends CoffeeScreen {
	private static final int KEY_W = 112;

	/** One row: its section, its name and the key it edits. */
	private record Bind(String section, String label, KeybindButton.Target target) {
	}

	private List<Bind> binds = List.of();
	private final Map<SettingRow, Bind> rows = new LinkedHashMap<>();
	private final Map<String, Label> titles = new HashMap<>();
	private ScrollList list;
	private int shown;
	/** Key code to the binds using it. */
	private Map<Integer, List<Bind>> shared = Map.of();

	public KeybindsScreen(@Nullable Screen parent) {
		super("Keybinds", parent);
	}

	@Override
	protected @Nullable KeyMapping toggleKey() {
		return CoffeeClient.keybinds().keybindList;
	}

	@Override
	protected void build() {
		int[] frame = centred43(560);
		int x0 = frame[0] + 4, y0 = frame[1], contentW = frame[2] - 8;
		int top = addHeader(x0, y0 + 4, contentW, "Keybinds");
		Panel panel = root.add(new Panel("Binds").subtitle(() -> shown + " shown"));
		panel.setBounds(x0, top, contentW, y0 + frame[3] - top - 4);
		TextField search = root.add(new TextField().placeholder("Search binds...").maxLength(32).onChange(this::filter));
		search.setBounds(panel.contentX(), panel.contentY(), panel.contentWidth(), 14);
		ScrollList list = root.add(new ScrollList().padding(1));
		int listY = panel.contentY() + 18;
		list.setBounds(panel.contentX(), listY, panel.contentWidth(), panel.y + panel.height - listY - Panel.PAD);
		binds = binds(this::refreshConflicts);
		refreshConflicts();
		rows.clear();
		titles.clear();
		this.list = list;
		String section = null;
		for (Bind b : binds) {
			if (!b.section().equals(section)) {
				section = b.section();
				Label title = list.add(new Label(section, Label.Style.TITLE).indent(4));
				title.setBounds(0, 0, 0, 14);
				titles.put(section, title);
			}
			SettingRow row = list.add(new SettingRow(() -> label(b), new KeybindButton(b.target()), KEY_W, 14));
			rows.put(row, b);
		}
		filter("");
	}

	/** Shows only the binds whose name, section or key matches {@code query}; a section with none left hides too. */
	private void filter(String query) {
		String q = query.trim().toLowerCase(Locale.ROOT);
		Map<String, Boolean> any = new HashMap<>();
		shown = 0;
		for (Map.Entry<SettingRow, Bind> e : rows.entrySet()) {
			Bind b = e.getValue();
			boolean match = q.isEmpty() || b.label().toLowerCase(Locale.ROOT).contains(q)
				|| b.section().toLowerCase(Locale.ROOT).contains(q) || b.target().display().toLowerCase(Locale.ROOT).contains(q);
			e.getKey().visible = match;
			if (match) {
				shown++;
				any.put(b.section(), true);
			}
		}
		titles.forEach((section, label) -> label.visible = any.getOrDefault(section, false));
		if (list != null) list.scrollToTop();
	}

	/** Every bind on the page. {@code changed} runs after one is set, so the conflict notes follow it. */
	private static List<Bind> binds(Runnable changed) {
		List<Bind> all = new ArrayList<>();
		for (Keybinds.Entry entry : CoffeeClient.keybinds().entries()) {
			KeyMapping mapping = entry.mapping();
			all.add(new Bind("Client keys", entry.label(), new KeybindButton.Target() {
				@Override
				public int key() {
					return KeyNames.boundKey(mapping);
				}

				@Override
				public void bind(int key) {
					Keybinds.rebind(mapping, key);
					changed.run();
				}

				@Override
				public String display() {
					return KeyNames.name(mapping);
				}
			}));
		}
		for (Macro macro : CoffeeClient.macros().all()) {
			if (!macro.trigger.isKey()) continue;
			all.add(new Bind("Macros", macro.name, new KeybindButton.Target() {
				@Override
				public int key() {
					return macro.key();
				}

				@Override
				public void bind(int key) {
					macro.setTriggerValue("key", Integer.toString(key));
					CoffeeClient.macros().changed();
					changed.run();
				}

				@Override
				public String display() {
					return KeyNames.name(macro.key());
				}
			}));
		}
		for (Category category : Category.values()) {
			for (Module module : CoffeeClient.modules().byCategory(category)) {
				all.add(new Bind("Modules", module.name(), new KeybindButton.Target() {
					@Override
					public int key() {
						return module.keybind().key();
					}

					@Override
					public void bind(int key) {
						module.keybind().set(key);
						changed.run();
					}

					@Override
					public String display() {
						return module.keybind().display();
					}
				}));
			}
		}
		return all;
	}

	/** Groups the binds by key. Runs when the screen opens and after every change. */
	private void refreshConflicts() {
		Map<Integer, List<Bind>> byKey = new HashMap<>();
		for (Bind b : binds) {
			int key = b.target().key();
			if (KeyNames.isBound(key)) byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(b);
		}
		shared = byKey;
	}

	/** The row's name, plus the other binds on the same key. */
	private String label(Bind b) {
		List<Bind> same = shared.getOrDefault(b.target().key(), List.of());
		if (same.size() < 2) return b.label();
		String others = same.stream().filter(o -> o != b).map(Bind::label).collect(Collectors.joining(", "));
		return b.label() + "  (same key as " + others + ")";
	}
}
