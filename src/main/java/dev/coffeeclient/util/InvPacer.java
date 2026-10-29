package dev.coffeeclient.util;

import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;

import java.util.List;
import java.util.Random;

/**
 * Paces a module's inventory clicks: the module's own delay plus a random jitter for each click,
 * a delay before the first click, a chance to put a click off by a tick, and an optional
 * standing-still gate. The module registers {@link #settings()} and calls {@link #tick()} once per
 * tick, then {@link #ready} before each click.
 */
public final class InvPacer {
	public final NumberSetting jitter = new NumberSetting("Delay jitter", "Up to this many extra ticks, picked at random for each click", 0, 0, 10, 1);
	public final NumberSetting startDelay = new NumberSetting("Start delay", "Ticks before the first click", 0, 0, 20, 1);
	public final NumberSetting missChance = new NumberSetting("Miss chance", "Chance a click is put off by a tick", 0, 0, 50, 1).format(v -> (int) v + "%");
	public final BooleanSetting standStill = new BooleanSetting("Only standing still", "No clicks while you move", false);

	private final Random random;
	private int wait;

	public InvPacer() {
		this(new Random());
	}

	InvPacer(Random random) {
		this.random = random;
	}

	public List<Setting<?>> settings() {
		return List.of(jitter, startDelay, missChance, standStill);
	}

	/** A run of clicks begins: wait the start delay first. */
	public void start() {
		wait = startDelay.intValue();
	}

	public void tick() {
		if (wait > 0) wait--;
	}

	/** True when a click may go now; arms the wait before the next one ({@code delay} ticks plus jitter). */
	public boolean ready(int delay) {
		if (wait > 0 || standStill.get() && Player.isMoving()) return false;
		if (missChance.get() > 0 && random.nextDouble() * 100 < missChance.get()) {
			wait = 1;
			return false;
		}
		wait = delay + (jitter.intValue() > 0 ? random.nextInt(jitter.intValue() + 1) : 0);
		return true;
	}
}
