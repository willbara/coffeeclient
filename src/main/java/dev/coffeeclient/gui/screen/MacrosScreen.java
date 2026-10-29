package dev.coffeeclient.gui.screen;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.CoffeeScreen;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.UiState;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.gui.widget.Button;
import dev.coffeeclient.gui.widget.Container;
import dev.coffeeclient.gui.widget.CycleButton;
import dev.coffeeclient.gui.widget.Dropdown;
import dev.coffeeclient.gui.widget.FormRow;
import dev.coffeeclient.gui.widget.KeybindButton;
import dev.coffeeclient.gui.widget.Label;
import dev.coffeeclient.gui.widget.MenuList;
import dev.coffeeclient.gui.widget.Panel;
import dev.coffeeclient.gui.widget.ScrollList;
import dev.coffeeclient.gui.widget.TextField;
import dev.coffeeclient.gui.widget.Toggle;
import dev.coffeeclient.gui.widget.Widget;
import dev.coffeeclient.macro.Macro;
import dev.coffeeclient.macro.MacroBlocks;
import dev.coffeeclient.macro.MacroCondition;
import dev.coffeeclient.macro.MacroManager;
import dev.coffeeclient.macro.MacroStep;
import dev.coffeeclient.macro.MacroTrigger;
import dev.coffeeclient.macro.Param;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.KeyNames;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Macros: the list on the left; on the right the selected macro's trigger, repeat options and its
 * steps. Every step type and trigger declares its inputs ({@link Param}), and the editor builds the
 * matching fields, so new step types need no UI code.
 */
public class MacrosScreen extends CoffeeScreen {
	private static final int ROW = 18;
	private static final int LABEL_W = 62;

	private @Nullable Macro selected;
	private int seenRevision = -1;
	private ScrollList list;
	private Panel editorPanel;
	private ScrollList editor;
	private int listX, listY, listW, listH;

	public MacrosScreen(@Nullable Screen parent) {
		super("Macros", parent);
	}

	/** Opens with {@code macro} selected. */
	public MacrosScreen(@Nullable Screen parent, @Nullable Macro macro) {
		this(parent);
		this.selected = macro;
	}

	@Override
	protected @Nullable KeyMapping toggleKey() {
		return CoffeeClient.keybinds().macros;
	}

	private static MacroManager macros() {
		return CoffeeClient.macros();
	}

	@Override
	protected void build() {
		int[] frame = centred43(760);
		int x0 = frame[0] + 4, y0 = frame[1], contentW = frame[2] - 8;
		int top = addHeader(x0, y0 + 4, contentW, "Macros");

		listW = Math.max(150, (contentW - 6) * 2 / 7);
		root.add(new Button("+ New macro", () -> select(macros().add("Macro")))).setBounds(x0, top, listW, 14);
		listX = x0;
		listY = top + 18;
		listH = y0 + frame[3] - listY - 4;
		list = root.add(new ScrollList().padding(1));
		list.setBounds(listX + 1, listY + 1, listW - 2, listH - 2);

		editorPanel = root.add(new Panel("Edit macro").subtitle(() -> selected == null ? ""
			: macros().isRunning(selected) ? "running" : selected.enabled ? "ready" : "off"));
		editorPanel.setBounds(x0 + listW + 6, top, contentW - listW - 6, y0 + frame[3] - top - 4);
		editor = editorPanel.add(new ScrollList().padding(1));
		editor.setBounds(editorPanel.contentX() - 2, editorPanel.contentY(), editorPanel.contentWidth() + 4, editorPanel.contentHeight() - 20);
		seenRevision = -1;
		buildEditor();
	}

	private void select(@Nullable Macro macro) {
		selected = macro;
		buildEditor();
	}

	private void rebuildList() {
		list.clear();
		for (Macro macro : macros().all()) list.add(new MacroRow(macro));
		if (macros().all().isEmpty()) {
			list.add(new Label("No macros yet.", Label.Style.DIM).indent(6)).setBounds(0, 0, 0, 14);
			list.add(new Label("Click + New macro.", Label.Style.DIM).indent(6)).setBounds(0, 0, 0, 12);
		}
		if (selected != null && !macros().all().contains(selected)) select(null);
	}

	// ---- editor ----

	private void buildEditor() {
		editor.clear();
		editorPanel.clear();
		editorPanel.add(editor);
		Macro m = selected;
		if (m == null) {
			editor.add(new Label("Select a macro, or make a new one.", Label.Style.DIM).indent(4)).setBounds(0, 0, 0, 14);
			// wrapped to the editor so it reads whole on narrow windows
			String help = "A macro is a trigger plus steps: say things, toggle modules, press controls, wait, wait for conditions, look, "
				+ "switch slots, run other macros. If / Else if / Else / End and Repeat / End group steps into blocks.";
			for (String line : Draw.wrap(help, editor.width - 14)) editor.add(new Label(line, Label.Style.DIM).indent(4)).setBounds(0, 0, 0, 11);
			return;
		}

		editor.add(row((r, x, y, w) -> {
			r.place(new Label("Name", Label.Style.DIM), x, y + 3, LABEL_W, 12);
			r.place(new TextField().text(m.name).maxLength(32).onChange(s -> macros().rename(m, s)), x + LABEL_W, y, w - LABEL_W - 26, 14);
			r.place(new Toggle(() -> m.enabled, () -> {
				m.enabled = !m.enabled;
				macros().changed();
			}).tooltip("Enabled: off keeps the macro but never triggers it"), x + w - 21, y + 1, 20, 12);
		}));

		editor.add(row((r, x, y, w) -> {
			r.place(new Label("Trigger", Label.Style.DIM), x, y + 3, LABEL_W, 12);
			r.place(new Dropdown<>(() -> Arrays.asList(MacroTrigger.values()), () -> m.trigger, MacroTrigger::label, t -> {
				m.trigger = t;
				macros().changed();
				buildEditor();
			}).tooltip(m.trigger.description()), x + LABEL_W, y, w - LABEL_W, 14);
		}));
		for (Param p : m.trigger.params()) {
			editor.add(row((r, x, y, w) -> {
				r.place(new Label(p.label(), Label.Style.DIM), x + 8, y + 3, LABEL_W - 8, 12);
				r.place(field(p.kind(), p, () -> m.triggerValue(p.key()), v -> {
					m.setTriggerValue(p.key(), v);
					macros().changed();
				}), x + LABEL_W, y, w - LABEL_W, 14);
			}));
		}

		editor.add(row((r, x, y, w) -> {
			r.place(new Label("Repeat", Label.Style.DIM), x, y + 3, LABEL_W, 12);
			r.place(new TextField().text(Integer.toString(m.repeat)).maxLength(5).filter(s -> s.chars().allMatch(Character::isDigit)).onChange(s -> {
				m.repeat = s.isEmpty() ? 1 : Integer.parseInt(s);
				macros().changed();
			}).tooltip("Times to run each start; 0 = until stopped"), x + LABEL_W, y, 40, 14);
			r.place(new Label("0 = until stopped", Label.Style.DIM), x + LABEL_W + 46, y + 3, Math.max(0, w - LABEL_W - 46), 12);
		}));
		editor.add(row((r, x, y, w) -> {
			r.place(new Toggle(() -> m.retriggerStops, () -> {
				m.retriggerStops = !m.retriggerStops;
				macros().changed();
			}).leftAligned(), x + LABEL_W, y + 1, 20, 12);
			r.place(new Label("Starting again stops it", Label.Style.DIM), x + LABEL_W + 24, y + 3, Math.max(0, w - LABEL_W - 24), 12);
		}));
		if (m.trigger.isKey()) {
			editor.add(row((r, x, y, w) -> {
				r.place(new Toggle(() -> m.inScreens, () -> {
					m.inScreens = !m.inScreens;
					macros().changed();
				}).leftAligned(), x + LABEL_W, y + 1, 20, 12);
				r.place(new Label("Key works in menus too", Label.Style.DIM), x + LABEL_W + 24, y + 3, Math.max(0, w - LABEL_W - 24), 12);
			}));
		}

		editor.add(row((r, x, y, w) -> {
			r.place(new Label("Steps", Label.Style.TITLE), x, y + 4, 60, 12);
			Button[] add = new Button[1];
			add[0] = new Button("+ Add step", () -> {
				MenuList menu = new MenuList();
				for (MacroStep.Type type : MacroStep.Type.values()) {
					menu.add(type.label(), () -> {
						m.steps.add(new MacroStep(type));
						macros().changed();
						buildEditor();
					});
				}
				menu.openAt(add[0].x, add[0].y + add[0].height + 1, UiState.screenWidth(), UiState.screenHeight(), add[0].width);
			});
			int addW = Math.min(90, w - 44);
			r.place(add[0], x + w - addW, y, addW, 14);
		}));
		int[] depths = MacroBlocks.depths(m.steps);
		for (int i = 0; i < m.steps.size(); i++) editor.add(new StepRow(m, i, depths[i]));
		if (m.steps.isEmpty()) editor.add(new Label("No steps: add one above.", Label.Style.DIM).indent(4)).setBounds(0, 0, 0, 14);

		int by = editorPanel.contentY() + editorPanel.contentHeight() - 16;
		int bx = editorPanel.contentX(), bw = editorPanel.contentWidth();
		int third = (bw - 8) / 3;
		editorPanel.add(new Button(() -> macros().isRunning(m) ? "Stop" : "Run", () -> {
			if (macros().isRunning(m)) macros().stop(m);
			else macros().run(m);
		})).setBounds(bx, by, third, 16);
		editorPanel.add(new Button("Copy", () -> select(macros().duplicate(m))).tooltip("Duplicate this macro")).setBounds(bx + third + 4, by, third, 16);
		editorPanel.add(new Button("Delete", () -> {
			macros().remove(m);
			select(null);
		}).danger()).setBounds(bx + bw - third, by, third, 16);
	}

	/** One step: number, type, move/copy/remove on the first line; its inputs on the second. Steps inside a block are indented. */
	private final class StepRow extends Container implements ScrollList.Relayout {
		private static final int INDENT = 10;
		private final Macro macro;
		private final int index;
		private final int depth;
		private final Dropdown<MacroStep.Type> type;
		private final List<Button> icons = new ArrayList<>();
		/** Inputs on the second line; wide ones (free text) share the room the others leave. */
		private final List<Widget> fields = new ArrayList<>();
		private final List<Boolean> wide = new ArrayList<>();

		StepRow(Macro macro, int index, int depth) {
			this.macro = macro;
			this.index = index;
			this.depth = depth;
			MacroStep step = macro.steps.get(index);
			List<Param> inputs = inputs(step);
			this.height = inputs.isEmpty() ? 22 : 40;
			tooltip(step.type().description());

			type = add(new Dropdown<>(() -> Arrays.asList(MacroStep.Type.values()), step::type, MacroStep.Type::label, t -> {
				step.setType(t);
				macros().changed();
				buildEditor();
			}));
			icon("\u2715", "Remove step", true, () -> {
				macro.steps.remove(index);
				macros().changed();
				buildEditor();
			});
			icon("\u29C9", "Duplicate step", true, () -> {
				macro.steps.add(index + 1, step.copy());
				macros().changed();
				buildEditor();
			});
			icon("\u25BC", "Move down", index < macro.steps.size() - 1, () -> swap(index, index + 1));
			icon("\u25B2", "Move up", index > 0, () -> swap(index, index - 1));

			for (Param p : inputs) {
				Widget w = field(kindOf(step, p), p, () -> step.get(p.key()), v -> {
					step.set(p.key(), v);
					macros().changed();
					// Changing these changes which inputs the step shows.
					if (p.kind() == Param.Kind.CONDITION || p.kind() == Param.Kind.CHOICE) buildEditor();
				});
				w.tooltip(p.label());
				fields.add(add(w));
				wide.add(p.kind() == Param.Kind.TEXT && !"arg".equals(p.key()));
			}
		}

		private void icon(String glyph, String tip, boolean enabled, Runnable action) {
			Button b = new Button(glyph, () -> {
				if (enabled) action.run();
			});
			b.tooltip(tip);
			icons.add(add(b));
		}

		private void swap(int a, int b) {
			MacroStep s = macro.steps.get(a);
			macro.steps.set(a, macro.steps.get(b));
			macro.steps.set(b, s);
			macros().changed();
			buildEditor();
		}

		/** Narrow editors indent less, so deep blocks keep room for their inputs. */
		private int indent() {
			return Math.min(depth * INDENT, Math.max(0, (width - 200) / 2));
		}

		@Override
		public void relayout() {
			int left = x + 18 + indent();
			type.setBounds(left, y + 3, Math.max(40, Math.min(110, x + width - left - 4 - icons.size() * 17 - 4)), 14);
			for (int i = 0; i < icons.size(); i++) icons.get(i).setBounds(x + width - 18 - i * 17, y + 3, 15, 14);
			if (fields.isEmpty()) return;
			int gap = 3, avail = x + width - 4 - left;
			int wideCount = 0;
			for (boolean w : wide) if (w) wideCount++;
			int narrowCount = fields.size() - wideCount;
			int narrowW = wideCount == 0 ? Math.min(150, (avail - (narrowCount - 1) * gap) / narrowCount) : 80;
			int wideW = wideCount == 0 ? 0 : (avail - narrowCount * (narrowW + gap) - (wideCount - 1) * gap) / wideCount;
			int cx = left;
			for (int i = 0; i < fields.size(); i++) {
				int w = wide.get(i) ? Math.max(60, wideW) : narrowW;
				fields.get(i).setBounds(cx, y + 21, w, 14);
				cx += w + gap;
			}
		}

		@Override
		protected void draw(Ui ui) {
			relayout();
			Theme t = ui.theme;
			if (hover > 0.01f) Draw.rect(ui.g, x, y, width, height, t.accent((int) (hover * 0x10)));
			Draw.text(ui.g, Integer.toString(index + 1), x + 4, y + 6, macros().isRunning(macro) ? t.accent : t.dimText);
			// One guide line per enclosing block.
			for (int d = 0; d < depth; d++) Draw.rect(ui.g, x + 20 + d * INDENT, y, 1, height, Colors.withAlpha(t.accent, 0x60));
			if (!t.glass()) Draw.rect(ui.g, x + 4, y + height - 1, width - 8, 1, Colors.withAlpha(t.border, 0x50));
			drawChildren(ui);
		}
	}

	/** The inputs shown for a step: a condition's argument only when it takes one, and only the inputs its mode uses. */
	private static List<Param> inputs(MacroStep step) {
		List<Param> out = new ArrayList<>();
		for (Param p : step.type().params()) {
			if ("arg".equals(p.key()) && MacroCondition.parse(step.get("condition")).argLabel() == null) continue;
			if ("ticks".equals(p.key()) && step.type() == MacroStep.Type.KEY && !"Hold".equals(step.get("mode"))) continue;
			if (step.type() == MacroStep.Type.REPEAT && "Times".equals(step.get("mode")) != "count".equals(p.key()) && !"mode".equals(p.key())) continue;
			if ("macro".equals(p.key()) && step.type() == MacroStep.Type.STOP_MACRO && !"Other macro".equals(step.get("which"))) continue;
			out.add(p);
		}
		return out;
	}

	/** A condition's argument takes the condition's own kind of input. */
	private static Param.Kind kindOf(MacroStep step, Param p) {
		if ("arg".equals(p.key())) return MacroCondition.parse(step.get("condition")).argKind();
		return p.kind();
	}

	// ---- input fields ----

	private Widget field(Param.Kind kind, Param p, Supplier<String> get, Consumer<String> set) {
		return switch (kind) {
			case TEXT -> new TextField().text(get.get()).maxLength(256).placeholder(p.label()).onChange(set);
			case NUMBER -> new TextField().text(get.get()).maxLength(8).placeholder(p.label())
				.filter(s -> s.chars().allMatch(c -> Character.isDigit(c) || c == '-' || c == '.')).onChange(set);
			case CHOICE -> new CycleButton(get, dir -> {
				int i = p.options().indexOf(get.get());
				set.accept(p.options().get(Math.floorMod(i + dir, p.options().size())));
			});
			case CONDITION -> new Dropdown<>(() -> Arrays.asList(MacroCondition.values()), () -> MacroCondition.parse(get.get()),
				MacroCondition::label, c -> set.accept(c.name()));
			case KEY -> new KeybindButton(new KeybindButton.Target() {
				@Override
				public int key() {
					return (int) Math.round(parse(get.get()));
				}

				@Override
				public void bind(int key) {
					set.accept(Integer.toString(key));
				}

				@Override
				public String display() {
					return KeyNames.name(key());
				}
			});
			case MODULE -> suggestField(get, set, "Module", prefix -> {
				List<String> names = new ArrayList<>();
				for (Module module : CoffeeClient.modules().all()) names.add(module.name());
				return names;
			});
			case MACRO -> suggestField(get, set, "Macro", prefix -> {
				List<String> names = new ArrayList<>();
				for (Macro macro : macros().all()) if (macro != selected) names.add(macro.name);
				return names;
			});
			case KEY_MAPPING -> suggestField(get, set, "Control", prefix -> {
				List<String> names = new ArrayList<>();
				for (KeyMapping key : minecraft.options.keyMappings) names.add(key.getName());
				return names;
			});
		};
	}

	private static double parse(String s) {
		try {
			return Double.parseDouble(s.trim());
		} catch (RuntimeException e) {
			return -1;
		}
	}

	/**
	 * A text field with a pick list: type part of a name and click the arrow (or press Enter) to choose
	 * among the matches. Key mapping ids show their readable names in the list.
	 */
	private Widget suggestField(Supplier<String> get, Consumer<String> set, String placeholder,
								java.util.function.Function<String, List<String>> options) {
		TextField text = new TextField().text(get.get()).maxLength(64).placeholder(placeholder).onChange(set);
		Container box = new Container() {
			@Override
			protected void draw(Ui ui) {
				text.setBounds(x, y, width - 14, height);
				drawChildren(ui);
			}
		};
		box.add(text);
		Button pick = new Button("▾", () -> {
			String query = get.get().trim().toLowerCase(Locale.ROOT);
			MenuList menu = new MenuList();
			int shown = 0;
			for (String option : options.apply(query)) {
				String label = readable(option);
				if (!query.isEmpty() && !option.toLowerCase(Locale.ROOT).contains(query) && !label.toLowerCase(Locale.ROOT).contains(query)) continue;
				menu.add(label, () -> {
					set.accept(option);
					text.text(option);
				});
				if (++shown >= 15) break;
			}
			if (shown == 0) menu.add("No matches", () -> { });
			menu.openAt(box.x, box.y + box.height + 1, UiState.screenWidth(), UiState.screenHeight(), box.width);
		});
		pick.tooltip("Pick from matches (type part of the name first)");
		Container wrapper = new Container() {
			@Override
			protected void draw(Ui ui) {
				box.setBounds(x, y, width, height);
				pick.setBounds(x + width - 13, y, 13, height);
				drawChildren(ui);
			}
		};
		wrapper.add(box);
		wrapper.add(pick);
		return wrapper;
	}

	/** {@code key.jump} -> "Jump (key.jump)"; other names unchanged. */
	private static String readable(String option) {
		if (!option.startsWith("key.")) return option;
		return Component.translatable(option).getString() + " (" + option + ")";
	}

	private static FormRow row(FormRow.Layout layout) {
		return new FormRow(ROW, layout);
	}

	@Override
	protected void renderContent(Ui ui) {
		if (seenRevision != macros().revision()) {
			seenRevision = macros().revision();
			rebuildList();
		}
		Draw.panel(ui.g, ui.theme, listX, listY, listW, listH);
		super.renderContent(ui);
	}

	/** One macro: name, trigger summary, running dot. */
	private final class MacroRow extends Widget {
		private final Macro macro;

		MacroRow(Macro macro) {
			this.macro = macro;
			this.height = 22;
		}

		@Override
		protected void draw(Ui ui) {
			Theme t = ui.theme;
			boolean sel = macro == selected;
			Draw.listHighlight(ui.g, t, x, y, width, height, sel, hover, 0x18);
			boolean running = macros().isRunning(macro);
			int dot = running ? t.accent : macro.enabled ? Colors.withAlpha(t.text, 0x80) : t.error;
			if (t.glass()) dev.coffeeclient.gui.fx.Shapes.disc(ui.g, x + 7.5f, y + 6.5f, 2, dot);
			else Draw.rect(ui.g, x + 6, y + 5, 3, 3, dot);
			Draw.text(ui.g, Draw.trim(macro.name, width - 20), x + 13, y + 2, sel ? t.strongText() : macro.enabled ? t.text : t.dimText);
			Draw.text(ui.g, Draw.trim(triggerSummary(macro) + " · " + macro.steps.size() + " steps", width - 20), x + 13, y + 12, t.dimText);
			if (!t.glass()) Draw.rect(ui.g, x + 4, y + height - 1, width - 8, 1, Colors.withAlpha(t.border, 0x50));
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			select(macro);
			return true;
		}
	}

	private static String triggerSummary(Macro m) {
		return switch (m.trigger) {
			case KEY_PRESS, KEY_HOLD -> (m.trigger == MacroTrigger.KEY_HOLD ? "Hold " : "") + KeyNames.name(m.key());
			case INTERVAL -> "Every " + m.triggerValue("seconds") + " s";
			case CHAT -> "Chat \"" + m.triggerValue("text") + "\"";
			case LOW_HEALTH -> "Health ≤ " + m.triggerValue("health");
			default -> m.trigger.label();
		};
	}
}
