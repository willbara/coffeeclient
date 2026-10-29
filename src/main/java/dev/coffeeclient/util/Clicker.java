package dev.coffeeclient.util;

import java.util.Random;

/**
 * Human-looking click timing: the gaps between clicks are log-normal around a target CPS (people
 * click in bursts with the odd long pause, never at a steady beat), with a cap on clicks per tick
 * and an optional miss chance. Free of Minecraft types so it can be tested.
 */
public final class Clicker {
	private final Random random;
	/** When the next click is due, in ms; 0 before the first. */
	private long nextAt;

	public Clicker(Random random) {
		this.random = random;
	}

	/**
	 * Clicks due by {@code now} (ms), at most {@code maxPerTick}. {@code spread} is the log-normal
	 * sigma: 0 is a steady beat, 0.3 about a person's. Clicks past the cap are dropped, not saved up.
	 */
	public int due(long now, double cps, double spread, int maxPerTick) {
		if (nextAt == 0) nextAt = now;
		int clicks = 0;
		while (nextAt <= now && clicks < maxPerTick) {
			clicks++;
			nextAt += gap(cps, spread);
		}
		if (nextAt < now) nextAt = now + gap(cps, spread);
		return clicks;
	}

	/** Ms to the next click; the mean stays 1000 / cps whatever the spread. */
	long gap(double cps, double spread) {
		return Math.max(1, Math.round(1000 / cps * Math.exp(spread * random.nextGaussian() - spread * spread / 2)));
	}

	/** True with probability {@code chance}: skip this click. */
	public boolean miss(double chance) {
		return chance > 0 && random.nextDouble() < chance;
	}

	/** Starts over: the next {@link #due} clicks at once. */
	public void reset() {
		nextAt = 0;
	}
}
