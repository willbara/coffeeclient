package dev.coffeeclient.module.modules.movement.elytrafly.modes;

import dev.coffeeclient.module.modules.movement.ElytraFly;
import dev.coffeeclient.module.modules.movement.elytrafly.ElytraFlightMode;
import dev.coffeeclient.module.modules.movement.elytrafly.ElytraFlightModes;

/** Vanilla controls, but with no input you sink at Control fall scaled by how far down you look. */
public class Control extends ElytraFlightMode {
	public Control(ElytraFly elytraFly) {
		super(elytraFly, ElytraFlightModes.Control);
	}

	@Override
	public void handleFallMultiplier() {
		if (mc.player == null) return;
		velY = -elytraFly.controlFall.get() * Math.abs(Math.sin(Math.toRadians(mc.player.getXRot())));
	}
}
