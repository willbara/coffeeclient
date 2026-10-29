package dev.coffeeclient.module.modules.movement.elytrafly.modes;

import dev.coffeeclient.module.modules.movement.ElytraFly;
import dev.coffeeclient.module.modules.movement.elytrafly.ElytraFlightMode;
import dev.coffeeclient.module.modules.movement.elytrafly.ElytraFlightModes;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Vanilla glide plus a steady push along your view, up to Boost top speed: dive to build speed,
 * then pull up and the glide turns it into height.
 */
public class Boost extends ElytraFlightMode {
	public Boost(ElytraFly elytraFly) {
		super(elytraFly, ElytraFlightModes.Boost);
	}

	@Override
	public Vec3 adjustMove(Vec3 vanilla, Vec3 movement) {
		LocalPlayer p = mc.player;
		if (p == null || !p.isFallFlying() || vanilla.length() >= elytraFly.boostMax.get()) return movement;
		Vec3 out = vanilla.add(p.getLookAngle().scale(elytraFly.boostThrust.get()));
		p.setDeltaMovement(out);
		return out;
	}
}
