package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.anim.Anim;
import dev.coffeeclient.gui.fx.Shapes;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.gui.theme.ThemePreset;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.util.Colors;

/**
 * Grid of theme preset cards, each a miniature window in that preset's colours. Clicking a card applies
 * the preset; the active card wears marching ants and a lifted shadow.
 */
public class PresetPicker extends Widget implements ScrollList.Relayout {
	private static final int CARD_W = 92;
	private static final int CARD_H = 38;
	private static final int GAP = 5;
	private static final ThemePreset[] PRESETS = ThemePreset.values();

	private final Theme theme;
	private final EnumSetting<ThemePreset> setting;
	/** The active card clicked while it was tweaked: a second click within a few seconds resets it. */
	private ThemePreset armed;
	private long armedAt;
	private final Anim[] lift = new Anim[PRESETS.length];
	private int cols = 1;

	public PresetPicker(Theme theme) {
		this.theme = theme;
		this.setting = theme.preset;
		for (int i = 0; i < lift.length; i++) lift[i] = new Anim(16f);
		this.height = CARD_H + GAP * 2;
	}

	@Override
	public void relayout() {
		cols = Math.max(1, (width - GAP) / (CARD_W + GAP));
		int rows = (PRESETS.length + cols - 1) / cols;
		height = rows * (CARD_H + GAP) + GAP;
	}

	private int cardW() {
		return (width - GAP - cols * GAP) / cols;
	}

	private int cardX(int i) {
		return x + GAP + (i % cols) * (cardW() + GAP);
	}

	private int cardY(int i) {
		return y + GAP + (i / cols) * (CARD_H + GAP);
	}

	@Override
	protected void draw(Ui ui) {
		relayout();
		Theme t = ui.theme;
		int cw = cardW();
		for (int i = 0; i < PRESETS.length; i++) {
			ThemePreset p = PRESETS[i];
			int cx = cardX(i), cy = cardY(i);
			boolean over = ui.hoverAllowed && ui.mouseX >= cx && ui.mouseX < cx + cw && ui.mouseY >= cy && ui.mouseY < cy + CARD_H;
			boolean active = setting.get() == p;
			float l = lift[i].update(ui, over ? 1f : active ? 0.6f : 0f);
			int dy = -Math.round(l * 2);
			drawCard(ui, p, cx, cy + dy, cw, l, active);
			boolean confirming = armed == p && System.currentTimeMillis() - armedAt < 3000;
			if (confirming) {
				Draw.rect(ui.g, cx, cy + dy + CARD_H - 11, cw, 11, Colors.withAlpha(t.warn, 0xE0));
				Draw.textNoShadow(ui.g, Draw.trim("Click again: reset", cw - 4), cx + 2, cy + dy + CARD_H - 10, 0xFF1A1208);
			}
			if (over) ui.tooltip(p.label(), p == ThemePreset.CUSTOM
				? (theme.hasCustomTheme() ? "Your own theme" : "Make your own: tweak a theme, then Save as Custom")
				: active && theme.themeChanged() ? "Tweaked: click twice to reset it" : "Click to apply (each theme remembers its own settings)");
		}
	}

	private void drawCard(Ui ui, ThemePreset p, int cx, int cy, int cw, float lift, boolean active) {
		Theme t = ui.theme;
		ThemePreset.Palette pal = p.palette;
		boolean custom = p == ThemePreset.CUSTOM;
		int top = pal != null ? pal.panelTop() : custom ? 0xFF2B2B2B : 0xFF1C1813;
		int bottom = pal != null ? pal.panelBottom() : custom ? 0xFF1A1A1A : 0xFF0F0D0B;
		int header = pal != null ? pal.header() : custom ? 0xFF3A3A3A : 0xFF241F19;
		int border = pal != null ? pal.border() : custom ? 0xFF8C8C8C : 0xFF6E5E44;
		int a1 = p.swatchAccent(), a2 = p.swatchAccent2();
		if (p.glass() || t.glass()) {
			drawGlassCard(ui, p, cx, cy, cw, lift, active, top, bottom, border, a1, a2);
			return;
		}
		// lifted shadow
		Draw.rect(ui.g, cx + 2, cy + CARD_H, cw - 2, 1 + Math.round(lift * 2), Colors.withAlpha(0, 0x50));
		Draw.gradientV(ui.g, cx, cy, cw, CARD_H, top, bottom);
		Draw.rect(ui.g, cx + 1, cy + 1, cw - 2, 9, header);
		Draw.gradientH(ui.g, cx + 1, cy + 10, cw - 2, 2, a1, a2);
		// a tiny module list: two "enabled" rows in the accent, one plain
		for (int r = 0; r < 3; r++) {
			int ry = cy + 15 + r * 6;
			int rowColor = r < 2 ? Colors.withAlpha(Colors.lerp(a1, a2, r * 0.5f), 0x70) : Colors.withAlpha(header, 0xFF);
			Draw.rect(ui.g, cx + 4, ry, cw / 2 - 6, 4, rowColor);
		}
		// swatches
		Draw.rect(ui.g, cx + cw - 22, cy + 16, 8, 8, a1);
		Draw.rect(ui.g, cx + cw - 12, cy + 16, 8, 8, a2);
		Draw.border(ui.g, cx, cy, cw, CARD_H, active ? t.accent : Colors.lerp(border, a1, lift * 0.6f));
		if (active) Draw.dashedOutline(ui.g, cx - 2, cy - 2, cw + 4, CARD_H + 4, 3, (int) (ui.time * 12), t.accent(0xC0));
		int textColor = pal != null && luminance(top) > 0.5f ? 0xFF2A1E14 : 0xFFF2EBE0;
		Draw.textNoShadow(ui.g, Draw.trim(p.label(), cw - 6), cx + 3, cy + 1, textColor);
	}

	/** A rounded miniature: pill rows instead of bars, round swatches, and a glow on the active card. */
	private void drawGlassCard(Ui ui, ThemePreset p, int cx, int cy, int cw, float lift, boolean active,
							   int top, int bottom, int border, int a1, int a2) {
		Theme t = ui.theme;
		int r = 6;
		Shapes.shadow(ui.g, cx, cy + 2, cw, CARD_H, r, 4 + Math.round(lift * 3), Colors.withAlpha(0, 0x50 + Math.round(lift * 0x30)));
		if (active) Shapes.halo(ui.g, cx, cy, cw, CARD_H, r, 5, t.accent(0x70));
		Shapes.roundRectV(ui.g, cx, cy, cw, CARD_H, r, top, bottom);
		boolean glassy = p.glass();
		for (int row = 0; row < 3; row++) {
			int ry = cy + 14 + row * 7;
			int rowColor = row == 0 ? Colors.withAlpha(a1, glassy ? 0x50 : 0x80) : Colors.withAlpha(Colors.lerp(top, 0xFFFFFFFF, 0.12f), 0xFF);
			Shapes.roundRect(ui.g, cx + 5, ry, cw / 2 - 6, 5, 2, rowColor);
		}
		if (glassy) Shapes.disc(ui.g, cx + cw / 2f - 4, cy + 16.5f, 1, a1);
		Shapes.disc(ui.g, cx + cw - 17.5f, cy + 20, 4, a1);
		Shapes.disc(ui.g, cx + cw - 8.5f, cy + 20, 4, a2);
		int rimTop = active ? t.accent : Colors.lerp(Colors.lerp(border, 0xFFFFFFFF, glassy ? 0.25f : 0f), a1, lift * 0.6f);
		Shapes.roundOutline(ui.g, cx, cy, cw, CARD_H, r, rimTop, Colors.fade(rimTop, 0.5f));
		int textColor = luminance(top) > 0.5f ? 0xFF2A1E14 : 0xFFF2EBE0;
		Draw.textNoShadow(ui.g, Draw.trim(p.label(), cw - 8), cx + 5, cy + 3, textColor);
	}

	private static float luminance(int c) {
		return (0.2126f * Colors.red(c) + 0.7152f * Colors.green(c) + 0.0722f * Colors.blue(c)) / 255f;
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button != LEFT) return false;
		int cw = cardW();
		for (int i = 0; i < PRESETS.length; i++) {
			int cx = cardX(i), cy = cardY(i);
			if (mx >= cx && mx < cx + cw && my >= cy && my < cy + CARD_H) {
				ThemePreset p = PRESETS[i];
				if (setting.get() == p) {
					// resetting the active theme throws its tweaks away, so it takes a second click
					if (!theme.themeChanged()) return true;
					if (!(armed == p && System.currentTimeMillis() - armedAt < 3000)) {
						armed = p;
						armedAt = System.currentTimeMillis();
						return true;
					}
					armed = null;
					theme.revertTheme();
				} else {
					// switching is safe: the theme being left remembers its tweaks
					armed = null;
					setting.set(p);
				}
				dev.coffeeclient.gui.fx.UiSounds.click(1.2f);
				return true;
			}
		}
		return false;
	}
}
