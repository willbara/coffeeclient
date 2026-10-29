package dev.coffeeclient.hud.elements;

import com.mojang.blaze3d.platform.InputConstants;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.module.modules.client.HudModule;
import dev.coffeeclient.util.KeyNames;

/** A small WASD + space keyboard that lights up the keys you are holding. */
public final class KeyboardElement extends HudElement {
	private static final int KEY = 12, GAP = 2, ROW_H = 14;

	public KeyboardElement() {
		super("keyboard", "Keyboard");
	}

	private HudModule hud() {
		return CoffeeClient.modules().get(HudModule.class);
	}

	@Override
	public boolean isEnabled() {
		return hud().isEnabled() && hud().keyboard.get();
	}

	@Override
	public void toggle() {
		hud().keyboard.toggle();
	}

	@Override
	public int width() {
		return 3 * KEY + 2 * GAP + 6;
	}

	@Override
	public int height() {
		return 3 * ROW_H + 4;
	}

	@Override
	public void render(Ui ui, int x, int y, boolean preview) {
		Theme t = ui.theme;
		if (hud().backgrounds.get()) Draw.hudBackground(ui.g, x, y, width(), height(), hud().backgroundColor.argb());
		int baseX = x + 3, baseY = y + 1;
		key(ui, baseX + KEY + GAP, baseY, KEY, "W", InputConstants.KEY_W);
		key(ui, baseX, baseY + ROW_H, KEY, "A", InputConstants.KEY_A);
		key(ui, baseX + KEY + GAP, baseY + ROW_H, KEY, "S", InputConstants.KEY_S);
		key(ui, baseX + 2 * (KEY + GAP), baseY + ROW_H, KEY, "D", InputConstants.KEY_D);
		key(ui, baseX, baseY + 2 * ROW_H, 3 * KEY + 2 * GAP, "Space", InputConstants.KEY_SPACE);
	}

	private void key(Ui ui, int x, int y, int w, String label, int code) {
		Theme t = ui.theme;
		boolean down = KeyNames.isKeyDown(code);
		Draw.box(ui.g, x, y, w, 12, down ? t.accent : t.control, down ? t.accent : t.border);
		Draw.textCentered(ui.g, label, x + w / 2, y + 2, down ? t.strongText() : t.dimText);
	}

	@Override
	protected int[] defaultPosition(int screenW, int screenH) {
		return new int[] {2, TextLinesElement.stackY(4)};
	}
}
