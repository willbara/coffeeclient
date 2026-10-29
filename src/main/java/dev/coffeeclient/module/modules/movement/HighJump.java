package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.NumberSetting;

/** Makes you jump higher than normal. */
public final class HighJump extends Module {
	public final NumberSetting multiplier = setting(new NumberSetting("Jump multiplier", "Jump height multiplier", 1, 0, 10, 0.1));

	public HighJump() {
		super("HighJump", Category.MOVEMENT, "Makes you jump higher than normal.");
	}
}
