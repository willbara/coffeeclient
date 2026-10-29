package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Player;
import net.minecraft.world.phys.Vec3;

/** Jumps for you. */
public final class AutoJump extends Module {
	public enum Mode {
		Jump, LowHop
	}

	public enum JumpWhen {
		Sprinting, Walking, Always
	}

	public final EnumSetting<Mode> mode = setting(new EnumSetting<>("Mode", "The method of jumping", Mode.Jump));
	public final EnumSetting<JumpWhen> jumpIf = setting(new EnumSetting<>("Jump if", "When to jump", JumpWhen.Always));
	public final NumberSetting velocityHeight = setting(new NumberSetting("Velocity height", "Distance that LowHop moves you", 0.25, 0, 2, 0.05));

	public AutoJump() {
		super("AutoJump", Category.MOVEMENT, "Automatically jumps.");
	}

	/** Only looks, shows or presses keys like the player would: never the cause of a setback. */
	@Override
	public boolean affectsMovement() {
		return false;
	}

	@Override
	public void onTick() {
		var player = mc().player;
		if (player == null || !player.onGround() || player.isShiftKeyDown() || !shouldJump()) return;

		if (mode.get() == Mode.Jump) {
			player.jumpFromGround();
		} else {
			Vec3 velocity = player.getDeltaMovement();
			player.setDeltaMovement(velocity.x, velocityHeight.get(), velocity.z);
		}
	}

	private boolean shouldJump() {
		return switch (jumpIf.get()) {
			case Sprinting -> mc().player.isSprinting() && Player.isMoving();
			case Walking -> Player.isMoving();
			case Always -> true;
		};
	}
}
