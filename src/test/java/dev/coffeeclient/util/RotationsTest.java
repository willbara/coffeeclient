package dev.coffeeclient.util;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotationsTest {
	@Test
	void everySmootherArrivesAndNeverOvershoots() {
		for (Rotations.Smoother smoother : Rotations.Smoother.values()) {
			float angle = 120, last = 0;
			int ticks = 0;
			while (angle > 0 && ticks++ < 200) {
				float step = smoother.step(angle, 20, last);
				assertTrue(step > 0 && step <= angle, smoother + " step " + step + " with " + angle + " left");
				angle -= step;
				last = step;
			}
			assertEquals(0, angle, 1e-4, smoother + " never arrived");
		}
	}

	@Test
	void linearCapsAtSpeedAndAccelerationRampsUp() {
		assertEquals(20, Rotations.Smoother.Linear.step(90, 20, 0));
		assertEquals(5, Rotations.Smoother.Acceleration.step(90, 20, 0));
		assertEquals(20, Rotations.Smoother.Acceleration.step(90, 20, 18));
	}

	@Test
	void snapLandsOnWholeMouseStepsAcrossTheWrap() {
		double sensitivity = 0.5;
		double f = sensitivity * 0.6 + 0.2;
		float step = (float) (f * f * f * 1.2);
		Rotations.Rotation snapped = Rotations.snap(new Rotations.Rotation(-175, 10), 170, 0, sensitivity);
		float yawSteps = (snapped.yaw() - 170) / step, pitchSteps = snapped.pitch() / step;
		assertEquals(Math.round(yawSteps), yawSteps, 1e-3);
		assertEquals(Math.round(pitchSteps), pitchSteps, 1e-3);
		// Short way round: 170 -> 185, not 170 -> -175.
		assertEquals(185, snapped.yaw(), step);
		assertEquals(-175, Mth.wrapDegrees(snapped.yaw()), step);
	}

	private static final AABB BOX = new AABB(-0.3, 0, 4.7, 0.3, 1.8, 5.3);
	private static final Vec3 EYES = new Vec3(0, 1.62, 0);

	@Test
	void leastRotationKeepsAnAimAlreadyOnTheBox() {
		Rotations.Rotation look = Rotations.needed(EYES, new Vec3(0.1, 1.0, 5));
		Vec3 point = Rotations.leastRotationPoint(BOX, EYES, look, p -> true);
		assertEquals(0, look.angleTo(Rotations.needed(EYES, point)), 0.01);
	}

	@Test
	void leastRotationPicksTheNearestUsableSample() {
		// Looking well to the left and up: the nearest sample is the inset top-left corner.
		Rotations.Rotation look = Rotations.needed(EYES, new Vec3(3, 4, 5));
		Vec3 point = Rotations.leastRotationPoint(BOX, EYES, look, p -> true);
		assertEquals(0.24, point.x, 1e-6);
		assertEquals(1.62, point.y, 1e-6);
		// Nothing usable: no point.
		assertNull(Rotations.leastRotationPoint(BOX, EYES, look, p -> false));
	}

	@Test
	void correctedKeysMoveWithinHalfAKeyStepOfWhereYouSteer() {
		for (int keys = 0; keys < 8; keys++) {
			double k = keys * Math.PI / 4;
			Vec2 input = new Vec2((float) Math.round(Math.cos(k)), (float) Math.round(Math.sin(k))).normalized();
			for (float client = -180; client < 180; client += 37) {
				for (float sent = -180; sent < 180; sent += 29) {
					Vec2 fixed = Rotations.correctInput(input, client, sent);
					assertEquals(1, fixed.length(), 1e-4);
					double want = worldAngle(input, client), got = worldAngle(fixed, sent);
					assertTrue(Math.abs(Mth.wrapDegrees(want - got)) <= 22.5 + 1e-3, input + " " + client + " -> " + sent);
				}
			}
		}
	}

	/** Direction (degrees) Entity#getInputVector moves a (left, forward) input at {@code yaw}. */
	private static double worldAngle(Vec2 input, float yaw) {
		double r = Math.toRadians(yaw);
		double x = input.x * Math.cos(r) - input.y * Math.sin(r);
		double z = input.y * Math.cos(r) + input.x * Math.sin(r);
		return Math.toDegrees(Math.atan2(z, x));
	}
}
