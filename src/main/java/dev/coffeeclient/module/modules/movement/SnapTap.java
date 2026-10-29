package dev.coffeeclient.module.modules.movement;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import net.minecraft.world.phys.Vec2;

import java.util.List;

/** With both strafe keys (or forward and back) held, the one pressed last wins instead of both cancelling out. */
public final class SnapTap extends Module {
	public final BooleanSetting forwardBack = setting(new BooleanSetting("Forward and back", "Also for forward and back", false));

	private boolean wasLeft, wasRight, wasUp, wasDown, leftLast, upLast;

	public SnapTap() {
		super("SnapTap", List.of("Snap Tap", "Null Binds"), Category.MOVEMENT, "The last strafe key pressed wins.");
	}

	@Override
	public void onTick() {
		var o = mc().options;
		if (o.keyLeft.isDown() && !wasLeft) leftLast = true;
		if (o.keyRight.isDown() && !wasRight) leftLast = false;
		if (o.keyUp.isDown() && !wasUp) upLast = true;
		if (o.keyDown.isDown() && !wasDown) upLast = false;
		wasLeft = o.keyLeft.isDown();
		wasRight = o.keyRight.isDown();
		wasUp = o.keyUp.isDown();
		wasDown = o.keyDown.isDown();
	}

	/** LocalPlayerMixin: the move vector (x left, y forward) with opposite keys resolved. */
	public static Vec2 resolve(Vec2 move) {
		SnapTap m = ModuleManager.ifOn(SnapTap.class);
		if (m == null) return move;
		var o = mc().options;
		// Vanilla's vector is just the key directions, normalised: rebuild it from their signs.
		float x = Math.signum(move.x), y = Math.signum(move.y);
		if (o.keyLeft.isDown() && o.keyRight.isDown()) x = m.leftLast ? 1 : -1;
		if (m.forwardBack.get() && o.keyUp.isDown() && o.keyDown.isDown()) y = m.upLast ? 1 : -1;
		if (x == 0 && y == 0) return move;
		return new Vec2(x, y).normalized();
	}
}
