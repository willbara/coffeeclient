package dev.coffeeclient.module.modules.movement.elytrafly.modes;

import dev.coffeeclient.module.modules.movement.ElytraFly;
import dev.coffeeclient.module.modules.movement.elytrafly.ElytraFlightMode;
import dev.coffeeclient.module.modules.movement.elytrafly.ElytraFlightModes;
import net.minecraft.world.phys.Vec3;

/**
 * Pitch40 mode: flies an endless 40-block window between two heights by alternating pitch angles.
 */
public class Pitch40 extends ElytraFlightMode {
	private boolean pitchingDown = true;
	private float pitch;
	private double lower, upper;

	public Pitch40(ElytraFly elytraFly) {
		super(elytraFly, ElytraFlightModes.Pitch40);
	}

	@Override
	public void onActivate() {
		if (mc.player == null) return;
		pitch = 37.72F;
		if (elytraFly.pitch40AutoBounds.get()) {
			upper = mc.player.getY();
			lower = upper - 40;
			return;
		}
		upper = elytraFly.pitch40upperBounds.get();
		lower = elytraFly.pitch40lowerBounds.get();

		if (mc.player.getY() < elytraFly.pitch40upperBounds.get()) {
			elytraFly.error("Player must be above upper bounds!");
			elytraFly.toggle();
		} else if (mc.player.getY() - 40 < elytraFly.pitch40lowerBounds.get()) {
			elytraFly.error("Player must be at least 40 blocks above the lower bounds!");
			elytraFly.toggle();
		}
	}

	/** Top of a climb: start diving, and with auto bounds move the window to this height. */
	private void apex() {
		pitchingDown = true;
		if (!elytraFly.pitch40AutoBounds.get() || mc.player == null) return;
		upper = mc.player.getY();
		lower = upper - 40;
	}

	/** Adds a random variance of +/- {@code bound / 2} to {@code pitch}. */
	private float randPitch(float pitch, float bound) {
		return (float) (pitch + (bound * (Math.random() - 0.5)));
	}

	@Override
	public void onTick() {
		super.onTick();
		if (mc.player == null) return;

		if (pitchingDown && mc.player.getY() <= lower) {
			pitchingDown = false;
		} else if (!pitchingDown && mc.player.getY() >= upper) {
			apex();
		}

		if (!pitchingDown) {
			pitch -= randPitch(elytraFly.pitch40rotationSpeedUp.get().floatValue(), 1.0F);
			if (pitch < -54.77F) {
				pitch = -54.77F;
				apex();
			}
		} else if (pitch < 37.72F) {
			pitch += randPitch(elytraFly.pitch40rotationSpeedDown.get().floatValue(), 0.50F);
		}

		mc.player.setXRot(pitch);
	}

	@Override
	public void autoTakeoff() {
	}

	@Override
	public void handleHorizontalSpeed(Vec3 movement) {
		velX = movement.x;
		velZ = movement.z;
	}

	@Override
	public void handleVerticalSpeed(Vec3 movement) {
	}

	@Override
	public void handleFallMultiplier() {
	}

	@Override
	public void handleAutopilot() {
	}
}
