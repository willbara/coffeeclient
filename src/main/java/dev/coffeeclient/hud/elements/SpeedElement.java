package dev.coffeeclient.hud.elements;

import dev.coffeeclient.hud.HudStats;

import java.util.List;

/** Horizontal speed in blocks (metres) per second. Follows the vehicle when riding. */
public final class SpeedElement extends TextLinesElement {
	public SpeedElement() {
		super("speed", "Speed");
	}

	@Override
	public boolean isEnabled() {
		return hud().isEnabled() && hud().speed.get();
	}

	@Override
	public void toggle() {
		hud().speed.toggle();
	}

	@Override
	protected List<String[]> lines() {
		double bps = HudStats.speed();
		return List.<String[]>of(new String[] {"Speed:", twoDecimals(bps)});
	}

	@Override
	protected int[] defaultPosition(int screenW, int screenH) {
		return new int[] {2, stackY(5)};
	}

	/** Per-frame text, so skip String.format's parser. */
	private static String twoDecimals(double v) {
		long hundredths = Math.round(Math.abs(v) * 100);
		long frac = hundredths % 100;
		return (v < 0 && hundredths != 0 ? "-" : "") + hundredths / 100 + (frac < 10 ? ".0" : ".") + frac;
	}
}
