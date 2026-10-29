package dev.coffeeclient.hud.elements;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.HudStats;
import dev.coffeeclient.module.modules.client.HudModule;
import dev.coffeeclient.util.Setback;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/** Warns when the server's time updates stop arriving (server lag / stall), and briefly after a setback. */
public final class LagNotifierElement extends HudElement {
	/** Ignore gaps shorter than this (ms) so ordinary jitter does not trigger it. */
	private static final long THRESHOLD_MS = 2000;
	/** How long a setback stays on show. */
	private static final long SETBACK_MS = 2000;

	public LagNotifierElement() {
		super("lag_notifier", "Lag notifier");
	}

	private HudModule hud() {
		return CoffeeClient.modules().get(HudModule.class);
	}

	@Override
	public boolean isEnabled() {
		return hud().isEnabled() && hud().lagNotifier.get();
	}

	@Override
	public boolean shouldRender() {
		return lagMillis() > 0 || Setback.recent(SETBACK_MS);
	}

	@Override
	public void toggle() {
		hud().lagNotifier.toggle();
	}

	private static long lagMillis() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return 0;
		long last = HudStats.lastServerUpdate();
		if (last == 0) return 0;
		long gap = System.currentTimeMillis() - last;
		return gap > THRESHOLD_MS ? gap : 0;
	}

	private static String label() {
		if (lagMillis() == 0 && Setback.recent(SETBACK_MS))
			return String.format(Locale.ROOT, "Setback %dt, %.2f off", Setback.lastRevertTicks(), Setback.lastDeviation());
		return String.format(Locale.ROOT, "Lag %.1fs", lagMillis() / 1000.0);
	}

	@Override
	public int width() {
		return TextLinesElement.gridded(Draw.width(label()) + 14);
	}

	@Override
	public int height() {
		return 14;
	}

	@Override
	public void render(Ui ui, int x, int y, boolean preview) {
		Theme t = ui.theme;
		int w = width(), h = height();
		if (hud().backgrounds.get()) Draw.hudBackground(ui.g, x, y, w, h, hud().backgroundColor.argb());
		Draw.text(ui.g, preview && !shouldRender() ? "Lag 1.0s" : label(), x + 5, y + 3, t.error);
	}

	@Override
	protected int[] defaultPosition(int screenW, int screenH) {
		return new int[] {screenW / 2, 4};
	}
}
