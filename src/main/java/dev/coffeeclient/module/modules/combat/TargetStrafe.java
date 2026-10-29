package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Rotations;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * While you hold forward and KillAura has a target, walks you in a circle around it at a set
 * radius, turning the other way when you bump into something. Only your keys change, so it moves
 * at your normal speed.
 */
public final class TargetStrafe extends Module {
	public final NumberSetting radius = setting(new NumberSetting("Radius", "Distance to circle at", 2.5, 1, 6, 0.25));
	public final NumberSetting range = setting(new NumberSetting("Range", "Only for targets this close", 6, 2, 12, 0.5));

	private int direction = 1;

	public TargetStrafe() {
		super("TargetStrafe", List.of("Target Strafe"), Category.COMBAT, "Circles KillAura's target while you hold forward.");
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player != null && player.horizontalCollision) direction = -direction;
	}

	/** LocalPlayerMixin: the yaw to walk along this tick, or NaN to walk as the keys say. */
	public static float heading() {
		TargetStrafe m = ModuleManager.ifOn(TargetStrafe.class);
		KillAura aura = ModuleManager.ifOn(KillAura.class);
		LocalPlayer player = mc().player;
		if (m == null || aura == null || player == null || !mc().options.keyUp.isDown()) return Float.NaN;
		Entity target = aura.target();
		if (target == null) return Float.NaN;
		double distance = Math.hypot(target.getX() - player.getX(), target.getZ() - player.getZ());
		if (distance > m.range.get()) return Float.NaN;
		float toTarget = Rotations.needed(target.position().add(0, player.getEyeHeight(), 0)).yaw();
		// Sideways keeps the distance; lean in when too far out, out when too close.
		float offset = 90 - Mth.clamp((float) (distance - m.radius.get()) * 30, -45, 45);
		return toTarget + m.direction * offset;
	}
}
