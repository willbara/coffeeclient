package dev.coffeeclient.module.modules.movement.elytrafly.modes;

import dev.coffeeclient.module.modules.movement.ElytraFly;
import dev.coffeeclient.module.modules.movement.elytrafly.ElytraFlightMode;
import dev.coffeeclient.module.modules.movement.elytrafly.ElytraFlightModes;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Accelerates along your heading, forward or back, but never past Constant speed: each tick adds
 * the most acceleration that keeps the new horizontal speed at or under the cap.
 */
public class Constant extends ElytraFlightMode {
	public Constant(ElytraFly elytraFly) {
		super(elytraFly, ElytraFlightModes.Constant);
	}

	@Override
	public Vec3 adjustMove(Vec3 vanilla, Vec3 movement) {
		LocalPlayer p = mc.player;
		if (p == null || !p.isFallFlying()) return movement;
		float forward = p.input.getMoveVector().y;
		Vec3 out;
		if (forward == 0) {
			if (!elytraFly.constStop.get()) return movement;
			out = Vec3.ZERO;
		} else {
			double yaw = Math.toRadians(p.getYRot() + (forward > 0 ? 90 : -90));
			double x = Math.cos(yaw), z = Math.sin(yaw);
			double room = headroom(vanilla.x, vanilla.z, x, z, elytraFly.constSpeed.get());
			double ramp = Math.min(1, vanilla.horizontalDistance() / 0.5);
			double acc = Math.min(room, elytraFly.constAcceleration.get() / 20 * (0.1 + ramp * 0.9));
			out = new Vec3(vanilla.x + x * acc, vanilla.y, vanilla.z + z * acc);
		}
		p.setDeltaMovement(out);
		return out;
	}

	/** Largest step along unit (x, z) from velocity (vx, vz) that stays within {@code cap}; 0 when already past it. */
	static double headroom(double vx, double vz, double x, double z, double cap) {
		double along = x * vx + z * vz;
		double disc = along * along - (vx * vx + vz * vz - cap * cap);
		return disc < 0 ? 0 : Math.max(0, -along + Math.sqrt(disc));
	}
}
