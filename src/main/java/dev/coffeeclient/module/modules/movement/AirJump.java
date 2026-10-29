package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;

/** Lets you jump while in the air. */
public final class AirJump extends Module {
	public final BooleanSetting maintainLevel = setting(new BooleanSetting("Maintain level", "Hold your current Y level while the jump key is held", false));

	private int level;
	private boolean wasJumpDown;
	private boolean wasShiftDown;

	public AirJump() {
		super("AirJump", Category.MOVEMENT, "Lets you jump in the air.");
	}

	@Override
	public void onEnable() {
		if (mc().player != null) level = mc().player.blockPosition().getY();
		wasJumpDown = false;
		wasShiftDown = false;
	}

	@Override
	public void onTick() {
		var player = mc().player;
		if (player == null) return;

		boolean jumpDown = mc().options.keyJump.isDown();
		boolean shiftDown = mc().options.keyShift.isDown();

		if (mc().gui.screen() == null && !player.onGround()) {
			if (jumpDown && !wasJumpDown) {
				level = player.blockPosition().getY();
				player.jumpFromGround();
			} else if (shiftDown && !wasShiftDown) {
				level--;
			} else if (maintainLevel.get() && player.blockPosition().getY() == level && jumpDown) {
				player.jumpFromGround();
			}
		}

		wasJumpDown = jumpDown;
		wasShiftDown = shiftDown;
	}
}
