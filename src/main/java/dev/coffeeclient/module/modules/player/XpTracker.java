package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.ModuleHudElement;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.ActionSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Experience gained per minute, progress to the next level and an ETA. */
public final class XpTracker extends Module {
	public final NumberSetting window = setting(new NumberSetting("Window (s)", "Rate is averaged over this long", 60, 10, 600, 10));
	public final BooleanSetting showLevel = setting(new BooleanSetting("Level progress", "Level and percent to next", true));
	public final BooleanSetting showRate = setting(new BooleanSetting("Rate", "XP per minute", true));
	public final BooleanSetting showEta = setting(new BooleanSetting("Time to level", "Estimated time to the next level", true));
	public final BooleanSetting showSession = setting(new BooleanSetting("Session total", "XP gained since enabling", false));
	public final ActionSetting reset = setting(new ActionSetting("Reset", "Restart the measurement", this::reset));

	private final ArrayDeque<long[]> samples = new ArrayDeque<>();
	private int startTotal = -1;

	public XpTracker() {
		super("XpTracker", Category.PLAYER, "Tracks experience gained per minute.");
	}

	private void reset() {
		samples.clear();
		startTotal = -1;
	}

	@Override
	protected void onEnable() {
		reset();
	}

	@Override
	public void onTick() {
		var p = mc().player;
		if (p == null) {
			reset();
			return;
		}
		long now = System.currentTimeMillis();
		if (startTotal < 0) startTotal = p.totalExperience;
		samples.addLast(new long[] {now, p.totalExperience});
		while (!samples.isEmpty() && now - samples.peekFirst()[0] > window.get() * 1000) samples.removeFirst();
	}

	private double perMinute() {
		if (samples.size() < 2) return 0;
		long[] a = samples.peekFirst(), b = samples.peekLast();
		double minutes = (b[0] - a[0]) / 60000.0;
		return minutes <= 0 ? 0 : Math.max(0, (b[1] - a[1]) / minutes);
	}

	@Override
	public List<HudElement> hudElements() {
		return List.of(new ModuleHudElement(this, "xp", "XP tracker", () -> {
			List<String[]> rows = new ArrayList<>();
			var p = mc().player;
			if (p == null) return rows;
			if (showLevel.get()) rows.add(ModuleHudElement.row("Level", p.experienceLevel + " (" + Math.round(p.experienceProgress * 100) + "%)"));
			double rate = perMinute();
			if (showRate.get()) rows.add(ModuleHudElement.row("XP/min", String.format(Locale.ROOT, "%.0f", rate)));
			if (showEta.get()) {
				double needed = p.getXpNeededForNextLevel() * (1 - p.experienceProgress);
				String eta = rate <= 0 ? "-" : String.format("%d:%02d", (long) (needed / rate), (long) (needed / rate * 60) % 60);
				rows.add(ModuleHudElement.row("Next level", eta));
			}
			if (showSession.get() && startTotal >= 0) rows.add(ModuleHudElement.row("Gained", p.totalExperience - startTotal));
			return rows;
		}, (w, h) -> new int[] {w / 2 - 40, h - 104}));
	}
}
