package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.modules.movement.Timer;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Targets;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * Closes the gap on an enemy in a burst: while one is coming but not yet in range, the game runs a
 * little slow and banks the ticks it saves; once the enemy is in range they are spent at high speed.
 * Slow ticks pay for fast ones, so your packet rate evens out.
 */
public final class TickBase extends Module {
	/** Banking runs while an enemy is within this much beyond Range. */
	private static final double CHARGE_EXTRA = 6;

	public final NumberSetting range = setting(new NumberSetting("Range", "Burst while an enemy is this close", 5, 2, 8, 0.25));
	public final NumberSetting minRange = setting(new NumberSetting("Min range", "Stop the burst once this close", 2.5, 0, 6, 0.25));
	public final NumberSetting ticks = setting(new NumberSetting("Ticks", "Most ticks banked", 10, 1, 40, 1));
	public final NumberSetting timer = setting(new NumberSetting("Timer", "Game speed of the burst", 2, 1.1, 5, 0.1));
	public final NumberSetting charge = setting(new NumberSetting("Charge timer", "Game speed while banking", 0.8, 0.3, 0.95, 0.05));

	private double bank;

	public TickBase() {
		super("TickBase", List.of("Tick Base", "TimerRange"), Category.COMBAT, "Banks ticks as an enemy comes and spends them when it is in range.");
	}

	@Override
	protected void onDisable() {
		Timer.release(this);
		bank = 0;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null) return;
		Entity enemy = Targets.get(e -> e instanceof Player p && Targets.isAttackablePlayer(p) && Targets.withinRange(p, range.get() + CHARGE_EXTRA),
			Targets.SortPriority.LowestDistance);
		double distance = enemy == null ? Double.MAX_VALUE : Math.sqrt(Targets.distanceSq(enemy));
		if (enemy != null && distance <= range.get() && distance > minRange.get() && bank >= timer.get() - 1) {
			bank -= timer.get() - 1;
			Timer.override(this, timer.get());
		} else if (enemy != null && distance > range.get() && bank < ticks.get()) {
			bank += 1 - charge.get();
			Timer.override(this, charge.get());
		} else {
			Timer.release(this);
		}
	}

	@Override
	public String infoString() {
		return String.valueOf((int) bank);
	}
}
