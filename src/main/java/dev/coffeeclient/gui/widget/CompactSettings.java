package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.UiState;
import dev.coffeeclient.gui.fx.Shapes;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.setting.ActionSetting;
import dev.coffeeclient.setting.BlockPosSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.KeybindSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.setting.StringListSetting;
import dev.coffeeclient.setting.StringSetting;
import dev.coffeeclient.setting.Vector3dSetting;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.KeyNames;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/** Dense one-line setting editors used inside expanded ClickGUI module rows. */
public final class CompactSettings {
	public static final int ROW_H = 12;
	private static final int INDENT = 6;

	private CompactSettings() {
	}

	/** Build editors for a module's keybind and settings, grouped like the module screen (ungrouped first). */
	public static List<Widget> forSettings(KeybindSetting keybind, List<Setting<?>> settings) {
		List<Widget> out = new ArrayList<>();
		if (keybind != null) out.add(new KeyRow(keybind));
		Map<String, List<Setting<?>>> groups = new LinkedHashMap<>();
		groups.put("", new ArrayList<>());
		for (Setting<?> s : settings) groups.computeIfAbsent(s.group(), g -> new ArrayList<>()).add(s);
		for (var group : groups.entrySet()) {
			if (!group.getKey().isEmpty()) out.add(new GroupRow(group.getKey(), group.getValue()));
			for (Setting<?> s : group.getValue()) {
				Widget w = create(s);
				if (w != null) out.add(w);
			}
		}
		return out;
	}

	private static Widget create(Setting<?> s) {
		if (s instanceof ActionSetting a) return new ActionRow(a);
		if (s instanceof IdListSetting l) return new PickerRow(l);
		if (s instanceof BooleanSetting b) return new BoolRow(b);
		if (s instanceof NumberSetting n) return new SliderRow(n.name(), n::fraction, n::setFraction, n::display, n::stepBy, n);
		if (s instanceof EnumSetting<?> e) return new EnumRow(e);
		if (s instanceof ColorSetting c) return new ColorRow(c);
		if (s instanceof KeybindSetting k) return new KeyRow(k);
		if (s instanceof StringSetting str) return new StringRow(str);
		if (s instanceof StringListSetting list) return new ListRow(list);
		if (s instanceof Vector3dSetting v) return new VectorRow(v);
		if (s instanceof BlockPosSetting p) return new BlockPosRow(p);
		return null;
	}

	/** Shared row chrome: indent guide, hover wash, label. */
	abstract static class Row extends Widget {
		protected final Setting<?> setting;
		protected final String label;

		Row(String label, Setting<?> setting) {
			this.label = label;
			this.setting = setting;
			this.height = ROW_H;
			if (setting != null) tooltip(setting.description());
		}

		public boolean shown() {
			return setting == null || setting.isVisible();
		}

		protected void chrome(Ui ui) {
			Theme t = ui.theme;
			wash(ui, x, y, width, height, hover);
			guide(ui, x, y, height);
			Draw.text(ui.g, Draw.trim(label, Math.max(12, width - INDENT - valueWidth() - 4)), x + INDENT, y + 2, hover > 0.5f ? t.text : t.dimText);
		}

		/** Pixels the value needs on the right; the label gets the rest. */
		protected int valueWidth() {
			return width / 2;
		}

		@Override
		protected void draw(Ui ui) {
			chrome(ui);
			drawValue(ui);
		}

		protected abstract void drawValue(Ui ui);
	}

	/** Hover highlight of a row: an accent tint, or a faint rounded pill in the Glass style. */
	static void wash(Ui ui, int x, int y, int w, int h, float amount) {
		if (amount <= 0.01f) return;
		if (ui.theme.glass()) Shapes.roundRect(ui.g, x + 1, y, w - 2, h, Draw.controlRadius(w, h), Colors.withAlpha(0xFFFFFF, Math.round(amount * 0x0E)));
		else Draw.rect(ui.g, x, y, w, h, ui.theme.accent((int) (amount * 0x18)));
	}

	/** The thin accent indent line down the left of setting rows (Classic only; Glass rows sit in an inset well). */
	static void guide(Ui ui, int x, int y, int h) {
		if (!ui.theme.glass()) Draw.rect(ui.g, x + 2, y, 1, h, ui.theme.accent(0x70));
	}

	/** Section title over a setting group; hidden while every setting in it is hidden. */
	static final class GroupRow extends Row {
		private final List<Setting<?>> members;

		GroupRow(String label, List<Setting<?>> members) {
			super(label, null);
			this.members = members;
		}

		@Override
		public boolean shown() {
			return members.stream().anyMatch(Setting::isVisible);
		}

		@Override
		protected void draw(Ui ui) {
			Theme t = ui.theme;
			String text = Draw.trim(label, width - 10);
			Draw.text(ui.g, text, x + 3, y + 3, t.accent);
			int lineX = x + 6 + Draw.width(text);
			Draw.rect(ui.g, lineX, y + 7, x + width - 3 - lineX, 1, t.glass() ? Colors.withAlpha(t.border, 0x90) : t.accent(0x50));
		}

		@Override
		protected void drawValue(Ui ui) {
		}
	}

	static final class BoolRow extends Row {
		@Override
		protected int valueWidth() {
			return dev.coffeeclient.core.CoffeeClient.theme().glass() ? 18 : 12;
		}

		private final BooleanSetting s;
		private float on = -1;

		BoolRow(BooleanSetting s) {
			super(s.name(), s);
			this.s = s;
		}

		@Override
		protected void drawValue(Ui ui) {
			Theme t = ui.theme;
			float target = s.get() ? 1 : 0;
			on = on < 0 ? target : on + (target - on) * ui.animStep(16f);
			if (t.glass()) {
				// a small pill switch: the knob glides across and the track takes the accent
				int sw = 14, sh = 8, sx = x + width - sw - 2, sy = y + 2;
				if (on > 0.02f) Shapes.halo(ui.g, sx, sy, sw, sh, 4, 3, t.accent(Math.round(0x70 * on * Math.max(0.35f, t.glow.floatValue()))));
				Shapes.roundRect(ui.g, sx, sy, sw, sh, 4, Colors.lerp(Colors.lerp(t.control, 0xFFFFFFFF, 0.06f), t.accent(0xD0), on));
				Shapes.disc(ui.g, sx + 4 + on * (sw - 8), sy + 4, 3, Colors.lerp(0xFFC8CCC9, 0xFFFFFFFF, on));
				return;
			}
			int bx = x + width - 10, by = y + 2;
			Draw.box(ui.g, bx, by, 8, 8, t.control, Colors.lerp(t.border, t.accent, on));
			if (on > 0.02f) Draw.rect(ui.g, bx + 2, by + 2, 4, 4, Colors.fade(t.accent, on));
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			s.toggle();
			return true;
		}
	}

	/** Label + value, with a 1px track along the bottom; click/drag sets, wheel steps. */
	static final class SliderRow extends Row {
		@Override
		protected int valueWidth() {
			return Draw.width(display.get()) + 2;
		}

		private final DoubleSupplier fraction;
		private final DoubleConsumer setFraction;
		private final Supplier<String> display;
		private final java.util.function.IntConsumer step;
		private Supplier<Integer> trackColor;
		private boolean dragging;

		SliderRow(String label, DoubleSupplier fraction, DoubleConsumer setFraction, Supplier<String> display,
				  java.util.function.IntConsumer step, Setting<?> setting) {
			super(label, setting);
			this.fraction = fraction;
			this.setFraction = setFraction;
			this.display = display;
			this.step = step;
		}

		SliderRow track(Supplier<Integer> color) {
			this.trackColor = color;
			return this;
		}

		@Override
		protected void drawValue(Ui ui) {
			Theme t = ui.theme;
			Draw.textRight(ui.g, display.get(), x + width - 2, y + 2, dragging ? t.accent : t.text);
			int tx = x + INDENT, tw = width - INDENT - 2;
			int fill = (int) Math.round(tw * Math.max(0, Math.min(1, fraction.getAsDouble())));
			int color = trackColor != null ? trackColor.get() : t.accent;
			if (t.glass()) {
				Shapes.roundRect(ui.g, tx, y + height - 2, tw, 2, 1, Colors.withAlpha(0xFFFFFF, 0x16));
				Shapes.roundRect(ui.g, tx, y + height - 2, fill, 2, 1, color);
				if (dragging) Shapes.glowDot(ui.g, tx + fill, y + height - 1, 2, 3, Colors.withAlpha(color, 0xA0));
				Shapes.disc(ui.g, tx + fill, y + height - 1, 2, 0xFFF4F6F5);
				return;
			}
			Draw.rect(ui.g, tx, y + height - 2, tw, 1, t.control);
			Draw.rect(ui.g, tx, y + height - 2, fill, 1, color);
			Draw.rect(ui.g, tx + fill - 1, y + height - 3, 2, 3, t.strongText());
		}

		private void apply(double mx) {
			setFraction.accept((mx - (x + INDENT)) / Math.max(1.0, width - INDENT - 2));
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			if (button != LEFT) return false;
			dragging = true;
			apply(mx);
			return true;
		}

		@Override
		public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
			if (!dragging) return false;
			apply(mx);
			return true;
		}

		@Override
		public boolean mouseReleased(double mx, double my, int button) {
			boolean was = dragging;
			dragging = false;
			return was;
		}

		@Override
		public boolean mouseScrolled(double mx, double my, double amount) {
			step.accept(amount > 0 ? 1 : -1);
			return true;
		}
	}

	static final class EnumRow extends Row {
		@Override
		protected int valueWidth() {
			return Math.min(width / 2, Draw.width(s.display())) + 2;
		}

		private final EnumSetting<?> s;

		EnumRow(EnumSetting<?> s) {
			super(s.name(), s);
			this.s = s;
		}

		@Override
		protected void drawValue(Ui ui) {
			Draw.textRight(ui.g, Draw.trim(s.display(), width / 2 - 8), x + width - 2, y + 2, ui.theme.accent);
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			s.cycle(button == RIGHT ? -1 : 1);
			return true;
		}

		@Override
		public boolean mouseScrolled(double mx, double my, double amount) {
			s.cycle(amount > 0 ? -1 : 1);
			return true;
		}
	}

	/** Swatch; clicking opens inline R/G/B channel sliders. */
	static final class ColorRow extends Row {
		private final ColorSetting s;
		private final List<SliderRow> channels = new ArrayList<>();
		private boolean open;

		ColorRow(ColorSetting s) {
			super(s.name(), s);
			this.s = s;
			String[] names = {"Red", "Green", "Blue"};
			int[] colors = {0xFFD9534F, 0xFF5CB85C, 0xFF4F7FD9};
			for (int i = 0; i < 3; i++) {
				int ch = i, color = colors[i];
				channels.add(new SliderRow("  " + names[i], () -> s.channel(ch) / 255.0,
					f -> s.setChannel(ch, (int) Math.round(Math.max(0, Math.min(1, f)) * 255)),
					() -> Integer.toString(s.channel(ch)), d -> s.setChannel(ch, s.channel(ch) + d * 5), null).track(() -> color));
			}
		}

		private int totalHeight() {
			return ROW_H * (open ? 4 : 1);
		}

		@Override
		public boolean shown() {
			height = totalHeight();
			return super.shown();
		}

		@Override
		protected void draw(Ui ui) {
			height = totalHeight();
			Theme t = ui.theme;
			boolean overHead = ui.hoverAllowed && ui.mouseX >= x && ui.mouseX < x + width && ui.mouseY >= y && ui.mouseY < y + ROW_H;
			wash(ui, x, y, width, ROW_H, overHead ? 1f : 0f);
			guide(ui, x, y, height);
			Draw.text(ui.g, Draw.trim(label, width - INDENT - 16), x + INDENT, y + 2, overHead ? t.text : t.dimText);
			Draw.box(ui.g, x + width - 10, y + 2, 8, 8, Colors.withAlpha(s.argb(), 255), open ? t.accent : t.border);
			if (open) {
				for (int i = 0; i < channels.size(); i++) {
					SliderRow r = channels.get(i);
					r.setBounds(x + 4, y + ROW_H * (i + 1), width - 4, ROW_H);
					r.render(ui);
				}
			}
		}

		@Override
		protected void drawValue(Ui ui) {
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			if (my < y + ROW_H) {
				open = !open;
				height = totalHeight();
				return true;
			}
			for (SliderRow r : channels) if (r.contains(mx, my)) return r.mouseClicked(mx, my, button);
			return true;
		}

		@Override
		public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
			for (SliderRow r : channels) if (r.mouseDragged(mx, my, button, dx, dy)) return true;
			return false;
		}

		@Override
		public boolean mouseReleased(double mx, double my, int button) {
			boolean any = false;
			for (SliderRow r : channels) any |= r.mouseReleased(mx, my, button);
			return any;
		}

		@Override
		public boolean mouseScrolled(double mx, double my, double amount) {
			for (SliderRow r : channels) if (r.contains(mx, my)) return r.mouseScrolled(mx, my, amount);
			return false;
		}
	}

	static final class KeyRow extends Row {
		@Override
		protected int valueWidth() {
			return Math.min(width / 2, Draw.width(s.isBound() ? s.display() : "None")) + 2;
		}

		private final KeybindSetting s;

		KeyRow(KeybindSetting s) {
			super(s.name(), s);
			this.s = s;
		}

		@Override
		protected void drawValue(Ui ui) {
			Theme t = ui.theme;
			boolean listening = isFocused();
			String v = listening ? "..." : s.isBound() ? s.display() : "None";
			int c = listening ? t.accent : s.isBound() ? t.text : t.dimText;
			Draw.textRight(ui.g, Draw.trim(v, width / 2 - 8), x + width - 2, y + 2, c);
			if (listening) ui.tooltip("Press a key or mouse button", "Esc cancels, Backspace clears");
		}

		@Override
		public boolean mouseBinding(double mx, double my, int button) {
			if (!isFocused() || !contains(mx, my)) return false;
			s.set(KeyNames.mouseKey(button));
			UiState.clearFocus();
			return true;
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			if (button == RIGHT) {
				s.clear();
				UiState.clearFocus();
			} else {
				UiState.focus(isFocused() ? null : this);
			}
			return true;
		}

		@Override
		public boolean keyPressed(int key, int scancode, int modifiers) {
			if (!isFocused()) return false;
			if (key == InputConstants.KEY_BACKSPACE || key == InputConstants.KEY_DELETE) s.clear();
			else if (key != InputConstants.KEY_ESCAPE) s.set(key);
			UiState.clearFocus();
			return true;
		}
	}

	/** A button: runs the action on click. */
	static final class ActionRow extends Row {
		private final ActionSetting s;

		ActionRow(ActionSetting s) {
			super(s.name(), s);
			this.s = s;
		}

		@Override
		protected void chrome(Ui ui) {
			Theme t = ui.theme;
			wash(ui, x, y, width, height, hover);
			guide(ui, x, y, height);
			Draw.arrow(ui.g, x + INDENT, y + 3, 3, 1, t.accent);
			Draw.text(ui.g, Draw.trim(label, width - 14), x + INDENT + 6, y + 2, hover > 0.5f ? t.strongText() : t.accent);
		}

		@Override
		protected void drawValue(Ui ui) {
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			if (button != LEFT) return false;
			s.run();
			return true;
		}
	}

	/** Opens the full-screen picker for a list setting. */
	static final class PickerRow extends Row {
		@Override
		protected int valueWidth() {
			return Math.min(width / 2, Draw.width(s.display())) + 12;
		}

		private final IdListSetting s;

		PickerRow(IdListSetting s) {
			super(s.name(), s);
			this.s = s;
		}

		@Override
		protected void drawValue(Ui ui) {
			Theme t = ui.theme;
			Draw.textRight(ui.g, Draw.trim(s.display(), width / 2 - 14), x + width - 9, y + 2, t.accent);
			Draw.arrow(ui.g, x + width - 6, y + 3, 3, 1, hover > 0.5f ? t.accent : t.dimText);
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			var mc = net.minecraft.client.Minecraft.getInstance();
			mc.gui.setScreen(new dev.coffeeclient.gui.screen.IdPickerScreen(mc.gui.screen(), s));
			return true;
		}
	}

	static final class StringRow extends Row {
		private final TextField field;

		StringRow(StringSetting s) {
			super(s.name(), s);
			this.field = new TextField().text(s.get()).maxLength(s.maxLength()).onChange(s::set);
		}

		@Override
		protected void drawValue(Ui ui) {
			field.setBounds(x + width / 2, y + 1, width / 2 - 1, ROW_H - 2);
			field.render(ui);
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			return field.mouseClicked(mx, my, button);
		}
	}

	/** Opens the full-screen editor for a free-text list setting. */
	static final class ListRow extends Row {
		@Override
		protected int valueWidth() {
			return Math.min(width / 2, Draw.width(s.display())) + 12;
		}

		private final StringListSetting s;

		ListRow(StringListSetting s) {
			super(s.name(), s);
			this.s = s;
		}

		@Override
		protected void drawValue(Ui ui) {
			Theme t = ui.theme;
			Draw.textRight(ui.g, Draw.trim(s.display(), width / 2 - 14), x + width - 9, y + 2, t.accent);
			Draw.arrow(ui.g, x + width - 6, y + 3, 3, 1, hover > 0.5f ? t.accent : t.dimText);
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			var mc = net.minecraft.client.Minecraft.getInstance();
			mc.gui.setScreen(new dev.coffeeclient.gui.screen.StringListScreen(mc.gui.screen(), s));
			return true;
		}
	}

	/** Three channel sliders folded underneath a header row (vectors, block positions). */
	abstract static class TripleRow extends Row {
		private final List<SliderRow> channels;
		private boolean open;

		TripleRow(String label, Setting<?> setting, SliderRow x, SliderRow y, SliderRow z) {
			super(label, setting);
			this.channels = List.of(x, y, z);
		}

		private int totalHeight() {
			return ROW_H * (open ? 4 : 1);
		}

		@Override
		public boolean shown() {
			height = totalHeight();
			return super.shown();
		}

		@Override
		protected void draw(Ui ui) {
			height = totalHeight();
			Theme t = ui.theme;
			boolean overHead = ui.hoverAllowed && ui.mouseX >= x && ui.mouseX < x + width && ui.mouseY >= y && ui.mouseY < y + ROW_H;
			wash(ui, x, y, width, ROW_H, overHead ? 1f : 0f);
			guide(ui, x, y, height);
			Draw.text(ui.g, Draw.trim(label, width - INDENT - 16), x + INDENT, y + 2, overHead ? t.text : t.dimText);
			Draw.arrow(ui.g, x + width - 7, y + 4, 3, open ? 0 : 1, overHead ? t.accent : t.dimText);
			if (open) {
				for (int i = 0; i < channels.size(); i++) {
					SliderRow r = channels.get(i);
					r.setBounds(x + 4, y + ROW_H * (i + 1), width - 4, ROW_H);
					r.render(ui);
				}
			}
		}

		@Override
		protected void drawValue(Ui ui) {
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			if (my < y + ROW_H) {
				open = !open;
				height = totalHeight();
				return true;
			}
			for (SliderRow r : channels) if (r.contains(mx, my)) return r.mouseClicked(mx, my, button);
			return true;
		}

		@Override
		public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
			for (SliderRow r : channels) if (r.mouseDragged(mx, my, button, dx, dy)) return true;
			return false;
		}

		@Override
		public boolean mouseReleased(double mx, double my, int button) {
			boolean any = false;
			for (SliderRow r : channels) any |= r.mouseReleased(mx, my, button);
			return any;
		}

		@Override
		public boolean mouseScrolled(double mx, double my, double amount) {
			for (SliderRow r : channels) if (r.contains(mx, my)) return r.mouseScrolled(mx, my, amount);
			return false;
		}
	}

	static final class VectorRow extends TripleRow {
		VectorRow(Vector3dSetting s) {
			super(s.name(), s,
				doubleChannel("X", s::x, s::setX, s.min(), s.max(), s.step()),
				doubleChannel("Y", s::y, s::setY, s.min(), s.max(), s.step()),
				doubleChannel("Z", s::z, s::setZ, s.min(), s.max(), s.step()));
		}

		private static SliderRow doubleChannel(String name, DoubleSupplier get, DoubleConsumer set, double min, double max, double step) {
			return new SliderRow(name,
				() -> max == min ? 0 : (get.getAsDouble() - min) / (max - min),
				f -> set.accept(min + Math.max(0, Math.min(1, f)) * (max - min)),
				() -> step >= 1 ? String.valueOf((long) get.getAsDouble()) : String.format(java.util.Locale.ROOT, "%.2f", get.getAsDouble()),
				d -> set.accept(get.getAsDouble() + d * step), null);
		}
	}

	static final class BlockPosRow extends TripleRow {
		BlockPosRow(BlockPosSetting s) {
			super(s.name(), s,
				intChannel("X", s::x, s::setX, s.min(), s.max()),
				intChannel("Y", s::y, s::setY, s.min(), s.max()),
				intChannel("Z", s::z, s::setZ, s.min(), s.max()));
		}

		private static SliderRow intChannel(String name, java.util.function.IntSupplier get, java.util.function.IntConsumer set, int min, int max) {
			return new SliderRow(name,
				() -> max == min ? 0 : (get.getAsInt() - min) / (double) (max - min),
				f -> set.accept((int) Math.round(min + Math.max(0, Math.min(1, f)) * (max - min))),
				() -> Integer.toString(get.getAsInt()),
				d -> set.accept(get.getAsInt() + d), null);
		}
	}
}
