package dev.coffeeclient.module.modules.movement.elytrafly.modes;

import dev.coffeeclient.module.modules.movement.ElytraFly;
import dev.coffeeclient.module.modules.movement.elytrafly.ElytraFlightMode;
import dev.coffeeclient.module.modules.movement.elytrafly.ElytraFlightModes;

/** Plain vanilla elytra control (steer with the movement keys). */
public class Vanilla extends ElytraFlightMode {
	public Vanilla(ElytraFly elytraFly) {
		super(elytraFly, ElytraFlightModes.Vanilla);
	}
}
