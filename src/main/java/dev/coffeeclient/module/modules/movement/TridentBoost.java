package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;

/** Boosts your riptide launch. */
public final class TridentBoost extends Module {
	public final NumberSetting boost = setting(new NumberSetting("Boost", "Velocity multiplier while using riptide", 2, 0.1, 20, 0.1));
	public final BooleanSetting outOfWater = setting(new BooleanSetting("Out of water", "Riptide works outside of water", true));

	public TridentBoost() {
		super("TridentBoost", Category.MOVEMENT, "Boosts you when using riptide with a trident.");
	}

	public double getMultiplier() {
		return isEnabled() ? boost.get() : 1;
	}

	public boolean allowOutOfWater() {
		return isEnabled() && outOfWater.get();
	}
}
