package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.anim.Ease;
import dev.coffeeclient.gui.anim.Motion;
import dev.coffeeclient.gui.fx.Ripple;
import dev.coffeeclient.gui.fx.Shapes;
import dev.coffeeclient.gui.fx.UiSounds;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.Colors;

import java.util.ArrayList;
import java.util.List;

/**
 * One module in a ClickGUI column. Left-click toggles; right-click or the +/- glyph expands the module's
 * settings inline. Enabled rows get the accent gradient and a faint travelling sheen.
 */
public class ModuleRow extends Widget {
	public static final int ROW_H = 14;
	/** Why the ClickGUI search lists a module (null: no search, or its name matched). */
	public static java.util.function.Function<Module, String> matchReason = m -> null;
	private static final int GLYPH_W = 12;

	private final Module module;
	private final int index;
	private List<Widget> editors;
	private boolean expanded;
	private float expand;
	private float enabled = -1;
	private boolean overHeader;
	private final Ripple ripple = new Ripple();

	/** Modules expanded in the ClickGUI, kept while the game runs (survives opening sub-windows). */
	private static final java.util.Set<String> EXPANDED = new java.util.HashSet<>();

	public ModuleRow(Module module, int index) {
		this.module = module;
		this.index = index;
		if (EXPANDED.contains(module.name())) {
			expanded = true;
			expand = 1f;
			editors();
		}
		this.height = ROW_H;
		tooltip(() -> {
			if (!overHeader) return List.of();
			String reason = matchReason.apply(module);
			return reason == null ? List.of(module.name(), module.description()) : List.of(module.name(), module.description(), "Matched " + reason);
		});
	}

	public Module module() {
		return module;
	}

	public void setExpanded(boolean expanded) {
		this.expanded = expanded;
		if (expanded) EXPANDED.add(module.name());
		else EXPANDED.remove(module.name());
		if (expanded) editors();
	}

	private List<Widget> editors() {
		if (editors == null) {
			List<Setting<?>> settings = new ArrayList<>();
			if (module.listable()) settings.add(module.shownInList());
			settings.addAll(module.settings());
			editors = CompactSettings.forSettings(module.isToggleable() ? module.keybind() : null, settings);
		}
		return editors;
	}

	private int settingsHeight() {
		if (editors == null) return 0;
		int h = 0;
		for (Widget w : editors) if (shown(w)) h += w.height;
		return h + 2;
	}

	private static boolean shown(Widget w) {
		return !(w instanceof CompactSettings.Row r) || r.shown();
	}

	/** Advance animations and return the height this row wants this frame. */
	public int animate(Ui ui) {
		expand += ((expanded ? 1f : 0f) - expand) * ui.animStep(14f);
		if (Math.abs((expanded ? 1f : 0f) - expand) < 0.01f) expand = expanded ? 1f : 0f;
		float target = module.isEnabled() ? 1f : 0f;
		enabled = enabled < 0 ? target : enabled + (target - enabled) * ui.animStep(12f);
		return ROW_H + Math.round(settingsHeight() * expand);
	}

	@Override
	protected void draw(Ui ui) {
		Theme t = ui.theme;
		boolean overRow = ui.hoverAllowed && ui.mouseX >= x && ui.mouseX < x + width && ui.mouseY >= y && ui.mouseY < y + ROW_H;
		overHeader = overRow;
		float rowHover = overRow ? hover : 0f;
		if (t.glass()) {
			drawGlass(ui, overRow, rowHover);
			return;
		}

		Draw.rect(ui.g, x, y, width, ROW_H, t.panel);
		if (enabled > 0.01f) {
			// the enabled fill grows in from the left, then flows with the accent
			int fillW = Math.round(width * Ease.OUT_CUBIC.apply(enabled));
			if (t.gleam.get()) {
				float from = index * 0.035f;
				float e = enabled;
				Draw.gradientH(ui.g, x, y, fillW, ROW_H, from, 0.25f,
					pos -> t.accentAt(pos, (int) ((0x66 - 0x44 * (pos - from) / 0.25f) * e)));
				Draw.rect(ui.g, x, y, width, 1, Colors.withAlpha(0xFFFFFF, (int) (0x18 * enabled)));
				if (t.sheen.get()) {
					Draw.sheen(ui.g, x, y, width, ROW_H, Draw.sheenPhase(ui.timeMs, 6f, index * 0.07f), Colors.withAlpha(0xFFFFFF, (int) (0x16 * enabled)));
				}
			} else {
				Draw.rect(ui.g, x, y, fillW, ROW_H, t.accent((int) (0x66 * enabled)));
			}
			Draw.rect(ui.g, x, y, 1, ROW_H, t.accentAt(index * 0.035f, (int) (0xE0 * enabled)));
		}
		// hover light sweeps in from the left edge
		if (rowHover > 0.01f) Draw.gradientH(ui.g, x, y, Math.round(width * Ease.OUT_CUBIC.apply(rowHover)), ROW_H,
			Colors.withAlpha(0xFFFFFF, (int) (0x1C * rowHover)), Colors.withAlpha(0xFFFFFF, (int) (0x08 * rowHover)));
		ui.g.enableScissor(x, y, x + width, y + ROW_H);
		ripple.draw(ui, Colors.withAlpha(t.strongText(), 0x90), width * 0.6f);
		ui.g.disableScissor();

		int textColor = Colors.lerp(t.text, t.strongText(), enabled);
		int nameX = x + 4;
		if (module.favorite()) {
			Draw.text(ui.g, "★", nameX, y + 3, t.accent);
			nameX += 8;
		}
		// the pulse meter only shows while the full name still fits beside it, so names are never cut for it
		int room = x + width - GLYPH_W - 2 - nameX;
		boolean pulse = enabled > 0.05f && t.motion() && Draw.width(module.name()) <= room - 10;
		Draw.text(ui.g, Draw.trim(module.name(), room), nameX, y + 3, textColor);
		if (pulse) drawPulse(ui, x + width - GLYPH_W - 7, y + 3, enabled);
		drawGlyph(ui, overRow, Colors.lerp(t.dimText, t.text, enabled));

		if (expand > 0.001f) drawEditors(ui);
	}

	/** The settings glyph on the right: an arrow for modules with their own window, else plus / minus. */
	private void drawGlyph(Ui ui, boolean overRow, int idle) {
		boolean overGlyph = overRow && ui.mouseX >= x + width - GLYPH_W;
		int glyphColor = overGlyph ? ui.theme.accent : idle;
		if (module.hasConfigScreen()) Draw.arrow(ui.g, x + width - 8, y + 3, 4, 1, glyphColor);
		else if (expanded) Draw.minus(ui.g, x + width - 9, y + 3, 5, glyphColor);
		else Draw.plus(ui.g, x + width - 9, y + 4, 5, glyphColor);
	}

	/**
	 * Glass row: no fill of its own (the card shows through). Enabled rows get an inset pill in the accent
	 * that grows in from the left and a status light that glows and slowly breathes; hover is a faint pill.
	 */
	private void drawGlass(Ui ui, boolean overRow, float rowHover) {
		Theme t = ui.theme;
		int px = x + 2, pw = width - 4, py = y + 1, ph = ROW_H - 2;
		int r = Draw.controlRadius(pw, ph);
		if (rowHover > 0.01f) Shapes.roundRect(ui.g, px, py, pw, ph, r, Colors.withAlpha(0xFFFFFF, Math.round(0x10 * rowHover)));
		if (enabled > 0.01f) {
			int fillW = Math.max(ph, Math.round(pw * Ease.OUT_CUBIC.apply(enabled)));
			Shapes.roundRect(ui.g, px, py, fillW, ph, r, t.accentAt(index * 0.035f, Math.round(0x2A * enabled)));
			Shapes.roundOutline(ui.g, px, py, fillW, ph, r, t.accentAt(index * 0.035f, Math.round(0x58 * enabled)), t.accentAt(index * 0.035f, Math.round(0x18 * enabled)));
		}
		ui.g.enableScissor(px, py, px + pw, py + ph);
		ripple.draw(ui, Colors.withAlpha(t.strongText(), 0x70), width * 0.6f);
		ui.g.disableScissor();

		int nameX = x + 6;
		if (module.favorite()) {
			Draw.text(ui.g, "★", nameX, y + 3, t.accent);
			nameX += 8;
		}
		// like the Classic pulse meter, the light only shows while the whole name fits beside it
		int room = x + width - GLYPH_W - 2 - nameX;
		boolean light = Draw.width(module.name()) <= room - 8;
		Draw.text(ui.g, Draw.trim(module.name(), room), nameX, y + 3, Colors.lerp(t.text, t.strongText(), enabled));
		if (enabled > 0.02f && light) {
			float breathe = t.motion() ? 0.75f + 0.25f * Motion.wave(ui.time + index * 0.21f, 2.6f, 0) : 1f;
			float lx = x + width - GLYPH_W - 4.5f, ly = y + ROW_H / 2f;
			Shapes.glowDot(ui.g, lx, ly, 2, 4, t.accent(Math.round(0x90 * enabled * breathe * Math.max(0.4f, t.glow.floatValue()))));
			Shapes.disc(ui.g, lx, ly, 2, t.accent(Math.round(0xFF * enabled)));
		}
		drawGlyph(ui, overRow, Colors.lerp(t.dimText, t.text, enabled * 0.6f));

		if (expand > 0.001f) drawEditors(ui);
	}

	/** A tiny three-bar meter that bounces while the module runs. */
	private void drawPulse(Ui ui, int px, int py, float strength) {
		for (int i = 0; i < 3; i++) {
			float v = 0.35f + 0.65f * Motion.wave(ui.time + index * 0.13f, 0.55f + i * 0.17f, i * 0.31f);
			int bh = Math.max(1, Math.round(7 * v * strength));
			Draw.rect(ui.g, px + i * 2, py + 7 - bh, 1, bh, ui.theme.accentAt(index * 0.035f + i * 0.05f, (int) (0xD0 * strength)));
		}
	}

	private void drawEditors(Ui ui) {
		Theme t = ui.theme;
		int top = y + ROW_H;
		int bottom = y + height;
		if (t.glass()) Shapes.roundRect(ui.g, x + 2, top, width - 4, height - ROW_H - 1, 5, Colors.withAlpha(0, 0x38));
		else Draw.rect(ui.g, x, top, width, height - ROW_H, Colors.shade(t.panel, 0.85f));
		ui.g.enableScissor(x, top, x + width, bottom);
		int cy = top + 1;
		boolean prevHover = ui.hoverAllowed;
		ui.hoverAllowed = prevHover && ui.mouseY >= top && ui.mouseY < bottom;
		int inset = t.glass() ? 3 : 1;
		for (Widget w : editors()) {
			if (!shown(w)) continue;
			w.setBounds(x + inset, cy, width - inset * 2, w.height);
			w.render(ui);
			cy += w.height;
		}
		ui.hoverAllowed = prevHover;
		ui.g.disableScissor();
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (my < y + ROW_H) {
			boolean onGlyph = mx >= x + width - GLYPH_W;
			ripple.spawn(mx, my);
			if (button == MIDDLE) {
				module.favorite(!module.favorite());
			} else if (module.hasConfigScreen() && (button == RIGHT || onGlyph || !module.isToggleable())) {
				var mc = net.minecraft.client.Minecraft.getInstance();
				mc.gui.setScreen(module.configScreen(mc.gui.screen()));
			} else if (button == RIGHT || onGlyph || !module.isToggleable()) {
				setExpanded(!expanded);
			} else if (button == LEFT) {
				module.toggle();
				UiSounds.toggle(module.isEnabled());
			}
			return true;
		}
		if (!expanded) return false;
		for (Widget w : editors()) {
			if (shown(w) && w.contains(mx, my)) return w.mouseClicked(mx, my, button);
		}
		return true;
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (editors == null) return false;
		for (Widget w : editors) if (w.mouseDragged(mx, my, button, dx, dy)) return true;
		return false;
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		if (editors == null) return false;
		boolean any = false;
		for (Widget w : editors) any |= w.mouseReleased(mx, my, button);
		return any;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		if (!expanded || my < y + ROW_H) return false;
		for (Widget w : editors()) {
			if (shown(w) && w.contains(mx, my)) return w.mouseScrolled(mx, my, amount);
		}
		return false;
	}
}
