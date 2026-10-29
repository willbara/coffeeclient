package dev.coffeeclient.gui.screen;

import dev.coffeeclient.config.ClientOptions;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.Keybinds;
import dev.coffeeclient.gui.CoffeeScreen;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.UiState;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.gui.widget.Button;
import dev.coffeeclient.gui.widget.CenteredRow;
import dev.coffeeclient.gui.widget.CycleButton;
import dev.coffeeclient.gui.widget.InlineGroup;
import dev.coffeeclient.gui.widget.KeybindButton;
import dev.coffeeclient.gui.widget.Label;
import dev.coffeeclient.gui.widget.PresetPicker;
import dev.coffeeclient.gui.widget.ScrollList;
import dev.coffeeclient.gui.widget.SectionHeader;
import dev.coffeeclient.gui.widget.SettingRow;
import dev.coffeeclient.gui.widget.Slider;
import dev.coffeeclient.gui.widget.TextField;
import dev.coffeeclient.gui.widget.Toggle;
import dev.coffeeclient.gui.widget.Widget;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.KeyNames;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * UI theme, mod options and keybinds in one scrolling list. Sections collapse on click, the search
 * field filters rows by name, and module binding rows carry an enable toggle.
 */
public class SettingsScreen extends CoffeeScreen {
	private static final int KEY_W = 112;
	/** Room for a toggle: a checkbox, or the wider Glass switch. */
	private static final int TOGGLE_W = 20;
	private static final int CONTROL_W = KEY_W + 5 + TOGGLE_W;

	/** Collapsed state per section, remembered for the session. */
	private static final Map<String, Boolean> COLLAPSED = new HashMap<>();

	private final class Section {
		final String key;
		final String title;
		final boolean collapsedByDefault;
		final SectionHeader header;
		final List<Widget> rows = new ArrayList<>();
		final List<Section> children = new ArrayList<>();

		Section(String key, String title, int level, boolean collapsedByDefault) {
			this.key = key;
			this.title = title;
			this.collapsedByDefault = collapsedByDefault;
			// while searching, matching sections are shown open regardless of their saved state
			this.header = new SectionHeader(title, level).collapsible(() -> collapsed() && !searching(), this::toggle);
		}

		boolean collapsed() {
			return COLLAPSED.getOrDefault(key, collapsedByDefault);
		}

		void toggle() {
			COLLAPSED.put(key, !collapsed());
			applyFilter();
		}
	}

	private final List<Section> sections = new ArrayList<>();
	private ScrollList list;
	private TextField search;
	private Widget footer;
	private int frameX, frameY, frameW, frameH;
	private long resetArmedAt;

	public SettingsScreen(@Nullable Screen parent) {
		super("Settings", parent);
	}

	@Override
	protected @Nullable KeyMapping toggleKey() {
		return CoffeeClient.keybinds().settings;
	}

	@Override
	protected void build() {
		int[] frame = centred43(460);
		int fx = frame[0], fy = frame[1], fw = frame[2], fh = frame[3];
		sections.clear();
		frameX = fx;
		frameW = fw;
		int y = fy + 4;
		String title = "Settings";
		root.add(new Label(title, Label.Style.TITLE)).setBounds(fx + 4, y, Draw.width(title) + 2, 14);
		root.add(new Button("Back", this::onClose)).setBounds(fx + fw - 60, y - 1, 56, 16);
		int searchX = fx + 4 + Draw.width(title) + 18;
		int searchW = Math.min(200, fx + fw - 64 - searchX);
		search = root.add(new TextField().placeholder("Search settings...").maxLength(32).onChange(s -> applyFilter()));
		search.setBounds(fx + fw - 64 - searchW, y, searchW, 14);

		frameY = y + 19;
		frameH = fy + fh - frameY - 4;
		list = root.add(new ScrollList().padding(1));
		list.setBounds(fx + 1, frameY + 1, fw - 2, frameH - 2);

		buildTheme();
		buildOptions();
		buildKeybinds();
		buildModuleKeybinds();

		footer = list.add(new CenteredRow(new Button(
			() -> System.currentTimeMillis() - resetArmedAt < 3000 ? "Click again to confirm" : "Reset all settings",
			this::resetAll).danger(), 140, 16, 26));
		applyFilter();
	}

	// ---- sections ----

	private Section open(String key, String title, @Nullable Section parent, boolean collapsedByDefault) {
		Section s = new Section(key, title, parent == null ? 0 : 1, collapsedByDefault);
		list.add(s.header);
		if (parent == null) sections.add(s);
		else parent.children.add(s);
		return s;
	}

	private void row(Section section, Widget row) {
		list.add(row);
		section.rows.add(row);
	}

	private void buildTheme() {
		Theme theme = theme();
		Section s = open("theme", "UI theme", null, false);
		row(s, new PresetPicker(theme));
		row(s, new dev.coffeeclient.gui.widget.ThemeSaveBar(theme));
		row(s, new dev.coffeeclient.gui.widget.ColorPickerRow(theme.textColor, false, false));
		row(s, cycle(theme.textFont));

		Section accent = open("theme/accent", "Accent", s, false);
		row(accent, new dev.coffeeclient.gui.widget.ColorPickerRow(theme.accentColor, false, false));
		row(accent, new dev.coffeeclient.gui.widget.ColorPickerRow(theme.windowBackgroundColor, false, false));
		row(accent, new dev.coffeeclient.gui.widget.ColorPickerRow(theme.accent2Color, false, false));
		row(accent, cycle(theme.accentMode));
		row(accent, slider(theme.accentSpeed));

		Section fx = open("theme/effects", "Effects", s, true);
		row(fx, cycle(theme.style));
		row(fx, cycle(theme.corners));
		row(fx, toggle(theme.gloss));
		row(fx, slider(theme.glow));
		row(fx, cycle(theme.shadow));
		row(fx, cycle(theme.texture));
		row(fx, slider(theme.textureStrength));
		row(fx, slider(theme.vignette));
		row(fx, cycle(theme.backdrop));
		row(fx, slider(theme.backdropStrength));
		row(fx, toggle(theme.gleam));
		row(fx, toggle(theme.sheen));

		Section motion = open("theme/motion", "Motion", s, true);
		row(motion, cycle(theme.transition));
		row(motion, cycle(theme.motionStyle));
		row(motion, stepper(theme.animationSpeed));
		row(motion, toggle(theme.reduceMotion));
		row(motion, toggle(theme.uiSounds));

		Section window = open("theme/window", "Window", s, true);
		row(window, slider(theme.opacity));
		row(window, toggle(theme.blur));
		row(window, slider(theme.backgroundDim));
		row(s, new SettingRow("Theme colors", new Button("Reset theme", theme::resetColors), CONTROL_W));
	}

	private void buildOptions() {
		ClientOptions options = CoffeeClient.options();
		Section s = open("options", "Mod options", null, false);
		row(s, slider(options.interfaceScale));
		row(s, stepper(options.dashboardLogHeight));
		row(s, slider(options.rotationHold));
		row(s, toggle(options.rotationGcd));
		row(s, slider(options.rotationJitter));
		row(s, described(new SettingRow(options.logLinesKept.name(), new TextField()
			.text(options.logLinesKept.display())
			.maxLength(5)
			.centered()
			.filter(str -> str.chars().allMatch(Character::isDigit))
			.onChange(str -> {
				if (!str.isEmpty()) options.logLinesKept.set(Double.parseDouble(str));
			}), CONTROL_W), options.logLinesKept.description()));
	}

	private void buildKeybinds() {
		Section s = open("keybinds", "Keybinds", null, false);
		for (Keybinds.Entry entry : CoffeeClient.keybinds().entries()) {
			KeyMapping mapping = entry.mapping();
			KeybindButton button = new KeybindButton(new KeybindButton.Target() {
				@Override
				public int key() {
					return KeyNames.boundKey(mapping);
				}

				@Override
				public void bind(int key) {
					Keybinds.rebind(mapping, key);
				}

				@Override
				public String display() {
					return KeyNames.name(mapping);
				}
			});
			row(s, new SettingRow(entry.label(), new InlineGroup().slot(button, KEY_W, 14).slot(null, TOGGLE_W, 10), CONTROL_W));
		}
	}

	private void buildModuleKeybinds() {
		Section parent = open("modules", "Module keybinds", null, false);
		for (Category category : Category.values()) {
			List<Module> modules = CoffeeClient.modules().byCategory(category);
			Section s = open("modules/" + category.name(), category.displayName(), parent, true);
			s.header.badge(modules.size() + " modules");
			for (Module module : modules) {
				KeybindButton button = new KeybindButton(new KeybindButton.Target() {
					@Override
					public int key() {
						return module.keybind().key();
					}

					@Override
					public void bind(int key) {
						module.keybind().set(key);
					}

					@Override
					public String display() {
						return module.keybind().display();
					}
				});
				Widget toggle = module.isToggleable()
					? new Toggle(module::isEnabled, module::toggle).tooltip(() -> List.of((module.isEnabled() ? "Disable " : "Enable ") + module.name()))
					: null;
				SettingRow row = new SettingRow(module.name(), new InlineGroup().slot(button, KEY_W, 14).slot(toggle, TOGGLE_W, 10), CONTROL_W);
				row.tooltip(module.name(), module.description());
				row(s, row);
			}
		}
	}

	// ---- search / collapse ----

	private boolean searching() {
		return search != null && !search.text().isBlank();
	}

	private void applyFilter() {
		if (list == null) return;
		String q = search == null ? "" : search.text().trim().toLowerCase(Locale.ROOT);
		for (Section s : sections) visit(s, q, true, false);
		if (footer != null) footer.visible = q.isEmpty();
	}

	/** @return true if anything in this section matches the query */
	private boolean visit(Section s, String q, boolean shown, boolean forced) {
		boolean searching = !q.isEmpty();
		boolean titleMatch = forced || (searching && s.title.toLowerCase(Locale.ROOT).contains(q));
		boolean open = searching || !s.collapsed();
		boolean any = false;
		for (Widget row : s.rows) {
			boolean match = !searching || titleMatch || rowText(row).contains(q);
			row.visible = shown && open && match;
			any |= searching && match;
		}
		for (Section child : s.children) any |= visit(child, q, shown && open, titleMatch);
		s.header.visible = shown && (!searching || any || titleMatch);
		return any || titleMatch;
	}

	private static String rowText(Widget row) {
		if (row instanceof dev.coffeeclient.gui.widget.ColorPickerRow c) return c.label().toLowerCase(Locale.ROOT);
		return row instanceof SettingRow r ? r.label().toLowerCase(Locale.ROOT) : "";
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (UiState.focused() == null && event.isAllowedChatCharacter() && !Character.isWhitespace(event.codepoint())) {
			search.focus();
		}
		return super.charTyped(event);
	}

	// ---- helpers ----

	private void resetAll() {
		if (System.currentTimeMillis() - resetArmedAt < 3000) {
			CoffeeClient.config().resetAll();
			resetArmedAt = 0;
			rebuildWidgets();
		} else {
			resetArmedAt = System.currentTimeMillis();
		}
	}

	private static SettingRow described(SettingRow row, String description) {
		row.tooltip(description);
		return row;
	}

	private static SettingRow stepper(NumberSetting setting) {
		return described(new SettingRow(setting.name(), new CycleButton(setting::display, setting::stepBy), CONTROL_W), setting.description());
	}

	private static SettingRow slider(NumberSetting setting) {
		return described(new SettingRow(setting.name(), new Slider(setting), CONTROL_W + 30), setting.description());
	}

	private static SettingRow cycle(EnumSetting<?> setting) {
		return described(new SettingRow(setting.name(), new CycleButton(setting::display, setting::cycle), CONTROL_W), setting.description());
	}

	private static SettingRow toggle(BooleanSetting setting) {
		return described(new SettingRow(setting::name, new Toggle(setting::get, setting::toggle), TOGGLE_W, 10), setting.description());
	}

	@Override
	protected void renderContent(Ui ui) {
		Draw.panel(ui.g, ui.theme, frameX, frameY, frameW, frameH);
		super.renderContent(ui);
	}
}
