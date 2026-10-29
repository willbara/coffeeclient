package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.NumberSetting;

/** Gives you longer reach. */
public final class Reach extends Module {
	public final NumberSetting blockReach = setting(new NumberSetting("Extra block reach", "Distance added to your block reach", 0, 0, 5, 0.1));
	public final NumberSetting entityReach = setting(new NumberSetting("Extra entity reach", "Distance added to your entity reach", 0, 0, 5, 0.1));

	public Reach() {
		super("Reach", Category.PLAYER, "Gives you super long arms.");
	}

	public double blockReach() {
		return isEnabled() ? blockReach.get() : 0;
	}

	public double entityReach() {
		return isEnabled() ? entityReach.get() : 0;
	}

	@Override
	public String infoString() {
		return blockReach.display();
	}
}
