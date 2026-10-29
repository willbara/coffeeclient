package dev.coffeeclient.module.modules.render;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;

/** Dropped items stop bobbing, spin at your chosen speed, and flat items lie down on the ground. */
public final class ItemPhysics extends Module {
	public final BooleanSetting layFlat = setting(new BooleanSetting("Lay flat", "Flat items (not blocks) lie on the ground", true));
	public final BooleanSetting noBob = setting(new BooleanSetting("No bobbing", "Items rest on the ground instead of floating", true));
	public final NumberSetting spinSpeed = setting(new NumberSetting("Spin speed", "0 = still, 1 = vanilla", 0.0, 0, 3, 0.1));

	public ItemPhysics() {
		super("ItemPhysics", Category.RENDER, "Dropped items lie flat on the ground.");
	}

	public static boolean active() {
		return ModuleManager.on(ItemPhysics.class);
	}

	public static ItemPhysics self() {
		return CoffeeClient.modules().get(ItemPhysics.class);
	}
}
