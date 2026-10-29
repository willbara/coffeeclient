package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.Setting;

/** Lets you use items and attack at the same time. */
public final class Multitask extends Module {
	public final BooleanSetting attackingEntities = setting(new BooleanSetting("Attacking entities", "Lets you attack entities while using an item", true));

	public Multitask() {
		super("Multitask", Category.PLAYER, "Lets you use items and attack at the same time.");
	}

	public boolean attackingEntities() {
		return isEnabled() && attackingEntities.get();
	}
}
