package dev.coffeeclient.module.modules.movement.elytrafly.modes;

import dev.coffeeclient.module.modules.movement.ElytraFly;
import dev.coffeeclient.module.modules.movement.elytrafly.ElytraFlightMode;
import dev.coffeeclient.module.modules.movement.elytrafly.ElytraFlightModes;
import dev.coffeeclient.util.Pid;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Hold mode: vanilla gliding with the pitch steered by a PID controller to hold an altitude or a
 * speed, never steeper than Max pitch. Pair it with fireworks below a height, and with AutoFly
 * for the heading, to travel unattended.
 */
public class Hold extends ElytraFlightMode {
	private final Pid altitude = new Pid(1.2, 0.04, 0.85);
	private final Pid speed = new Pid(6.75, 0.3, 4.5);

	public Hold(ElytraFly elytraFly) {
		super(elytraFly, ElytraFlightModes.Hold);
	}

	@Override
	public void onActivate() {
		super.onActivate();
		altitude.reset();
		speed.reset();
	}

	@Override
	public void onTick() {
		super.onTick();
		LocalPlayer p = mc.player;
		if (p == null || !p.isFallFlying()) return;
		double out = switch (elytraFly.holdTarget.get()) {
			// Pitch is positive looking down, so climbing needs the negated output.
			case Altitude -> -altitude.step(elytraFly.holdAltitude.get(), p.getY());
			case Speed -> speed.step(elytraFly.holdSpeed.get(), Math.sqrt(p.position().distanceToSqr(p.xo, p.yo, p.zo)) * 20);
		};
		double max = elytraFly.holdMaxPitch.get();
		p.setXRot((float) Mth.clamp(out, -max, max));
	}

	@Override
	public String getHudString() {
		return "Hold";
	}
}
