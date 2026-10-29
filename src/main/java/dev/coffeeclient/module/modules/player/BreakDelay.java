package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;

/** Changes the delay between breaking blocks. */
public final class BreakDelay extends Module {
	public final NumberSetting cooldown = setting(new NumberSetting("Cooldown", "Block break cooldown in ticks", 0, 0, 5, 1));
	public final BooleanSetting noInstaBreak = setting(new BooleanSetting("No insta break", "Prevents misbreaking instantly broken blocks", false));

	public BreakDelay() {
		super("BreakDelay", Category.PLAYER, "Changes the delay between breaking blocks.");
	}

	public int cooldown() {
		return cooldown.get().intValue();
	}
}
