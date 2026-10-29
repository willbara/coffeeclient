package dev.coffeeclient.hud.elements;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.anim.Motion;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.module.modules.client.HudModule;
import dev.coffeeclient.util.Colors;
import net.fabricmc.loader.api.FabricLoader;

/** The client's name in the corner: an accent chip, a flowing gradient bar, or a steaming cup. */
public final class WatermarkElement extends HudElement {
	private static final String NAME = "Coffee";
	private static final String SUFFIX = " Client";
	private final String version = FabricLoader.getInstance().getModContainer(CoffeeClient.MOD_ID)
		.map(c -> c.getMetadata().getVersion().getFriendlyString().replaceAll("\\+.*", "")).orElse("dev");

	public WatermarkElement() {
		super("watermark", "Watermark");
	}

	private HudModule hud() {
		return CoffeeClient.modules().get(HudModule.class);
	}

	@Override
	public boolean isEnabled() {
		return hud().isEnabled() && hud().watermark.get();
	}

	@Override
	public void toggle() {
		hud().watermark.toggle();
	}

	@Override
	public int width() {
		int text = Draw.width(NAME + SUFFIX);
		return switch (hud().watermarkStyle.get()) {
			case CHIP -> text + 14 + Draw.width(version);
			case GRADIENT -> text + 10 + Draw.width(version);
			case CUP -> text + 22;
		};
	}

	@Override
	public int height() {
		return hud().watermarkStyle.get() == HudModule.WatermarkStyle.CUP ? 22 : 13;
	}

	@Override
	protected int[] defaultPosition(int screenW, int screenH) {
		return new int[] {screenW / 2 - width() / 2, MARGIN};
	}

	@Override
	public void render(Ui ui, int x, int y, boolean preview) {
		Theme t = ui.theme;
		var g = ui.g;
		int bg = hud().backgroundColor.argb();
		switch (hud().watermarkStyle.get()) {
			case CHIP -> {
				int nw = Draw.width(NAME) + 6;
				Draw.gradientH(g, x, y, nw, 13, 0, 0.3f, t::accentAt);
				Draw.text(g, NAME, x + 3, y + 3, 0xFF141414);
				int rest = width() - nw;
				Draw.rect(g, x + nw, y, rest, 13, bg);
				Draw.text(g, SUFFIX.trim(), x + nw + 3, y + 3, t.strongText());
				Draw.text(g, version, x + width() - Draw.width(version) - 3, y + 3, t.dimText);
			}
			case GRADIENT -> {
				Draw.rect(g, x, y, width(), 13, bg);
				Draw.gradientH(g, x, y + 11, width(), 2, 0, 1, t::accentAt);
				Draw.text(g, NAME + SUFFIX, x + 4, y + 2, t.strongText());
				Draw.text(g, version, x + width() - Draw.width(version) - 3, y + 2, t.dimText);
			}
			case CUP -> {
				drawCup(ui, x + 2, y + 8);
				Draw.text(g, NAME, x + 20, y + 4, t.accentAt(0f));
				Draw.text(g, SUFFIX.trim(), x + 20 + Draw.width(NAME) + 3, y + 4, t.strongText());
				Draw.text(g, version, x + 20, y + 13, t.dimText);
			}
		}
	}

	/** A 14x12 pixel mug with rising steam. */
	private static void drawCup(Ui ui, int x, int y) {
		Theme t = ui.theme;
		var g = ui.g;
		int body = Colors.lerp(t.text, 0xFFFFFFFF, 0.2f);
		Draw.rect(g, x, y, 11, 1, body);
		Draw.rect(g, x, y + 1, 1, 8, body);
		Draw.rect(g, x + 10, y + 1, 1, 8, body);
		Draw.rect(g, x + 1, y + 9, 9, 1, body);
		Draw.rect(g, x + 2, y + 10, 7, 1, body);
		// handle
		Draw.rect(g, x + 11, y + 2, 2, 1, body);
		Draw.rect(g, x + 13, y + 3, 1, 3, body);
		Draw.rect(g, x + 11, y + 6, 2, 1, body);
		// coffee with a moving accent surface
		Draw.gradientV(g, x + 1, y + 2, 9, 7, t.accentAt(0f), Colors.shade(t.accent, 0.45f));
		Draw.rect(g, x + 1, y + 1, 9, 1, Colors.lerp(t.accent, 0xFFFFFFFF, 0.4f));
		if (!t.motion()) return;
		for (int w = 0; w < 3; w++) {
			for (int k = 0; k < 4; k++) {
				float f = (Motion.time() * 0.9f + k / 4f + w * 0.33f) % 1f;
				int sx = x + 2 + w * 3 + Math.round((float) Math.sin(f * 6 + w) * 1.5f);
				int sy = y - 1 - Math.round(f * 8);
				Draw.rect(g, sx, sy, 1, 1, Colors.withAlpha(t.text, Math.round(0xA0 * (1 - f))));
			}
		}
	}
}
