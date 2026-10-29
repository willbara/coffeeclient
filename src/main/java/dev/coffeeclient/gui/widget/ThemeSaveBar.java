package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.anim.Anim;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.util.Colors;

/**
 * Status row under the preset cards: which theme is active and, once a preset is tweaked, buttons to keep
 * the tweaks as your own Custom theme or reset the preset. Tweaks are remembered by the theme either way.
 */
public class ThemeSaveBar extends Container implements ScrollList.Relayout {
	private static final int SAVE_W = 92;
	private static final int RESET_W = 52;
	private final Theme theme;
	private final Button save;
	private final Button revert;
	private final Anim attention = new Anim(10f);

	public ThemeSaveBar(Theme theme) {
		this.theme = theme;
		this.height = SettingRow.HEIGHT;
		save = add(new Button("Save as Custom", theme::saveCustom));
		revert = add(new Button("Reset", theme::revertTheme));
		save.tooltip("Keep these settings as your Custom theme", "The preset itself goes back to how it ships");
		revert.tooltip("Put this theme back to how it ships");
	}

	@Override
	public void relayout() {
		save.setBounds(x + width - SAVE_W - 4, y + 3, SAVE_W, 14);
		revert.setBounds(x + width - SAVE_W - RESET_W - 8, y + 3, RESET_W, 14);
	}

	@Override
	protected void draw(Ui ui) {
		relayout();
		boolean changed = theme.themeChanged();
		float a = attention.update(ui, changed ? 1f : 0f);
		String name = theme.preset.display();
		if (a > 0.01f) Draw.rect(ui.g, x, y, width, height, Colors.withAlpha(theme.warn, Math.round(0x22 * a)));
		if (a > 0.01f) Draw.rect(ui.g, x, y, 2, height, Colors.withAlpha(theme.warn, Math.round(0xC0 * a)));
		String text = changed ? name + " is tweaked (remembered for this theme)" : "Theme: " + name;
		Draw.text(ui.g, Draw.trim(text, width - SAVE_W - RESET_W - 24), x + 8, y + (height - 8) / 2, changed ? theme.strongText() : theme.dimText);
		save.visible = changed;
		revert.visible = changed;
		drawChildren(ui);
	}
}
