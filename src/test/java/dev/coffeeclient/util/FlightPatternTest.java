package dev.coffeeclient.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlightPatternTest {
	/** A flyer at {@code speed} blocks per tick that turns at most {@code maxTurn} degrees per tick. */
	private static final class Flyer {
		double x, z;
		float yaw;
		double minX, maxX, minZ, maxZ;

		Flyer(float yaw) {
			this.yaw = yaw;
		}

		/** 0 = moves exactly where it faces; higher = more momentum, so the path lags the facing. */
		double inertia;
		private double vx, vz;

		void run(FlightPattern pattern, double speed, float maxTurn, int maxTicks) {
			for (int tick = 0; tick < maxTicks && !done(pattern, speed); tick++) {
				float want = pattern.yaw(x, z, speed);
				if (pattern.done()) break;
				float delta = (float) (((want - yaw) % 360 + 540) % 360 - 180);
				yaw += Math.max(-maxTurn, Math.min(maxTurn, delta));
				vx = vx * inertia + -Math.sin(Math.toRadians(yaw)) * speed * (1 - inertia);
				vz = vz * inertia + Math.cos(Math.toRadians(yaw)) * speed * (1 - inertia);
				x += vx;
				z += vz;
				minX = Math.min(minX, x);
				maxX = Math.max(maxX, x);
				minZ = Math.min(minZ, z);
				maxZ = Math.max(maxZ, z);
			}
		}

		private boolean done(FlightPattern pattern, double speed) {
			return pattern.done();
		}
	}

	@Test
	void spiralTightensToTheCentreBothWays() {
		for (boolean clockwise : new boolean[]{true, false}) {
			Flyer flyer = new Flyer(0);
			FlightPattern spiral = new FlightPattern.Spiral(0, 0, 0, clockwise, false, 200, 32, 6);
			flyer.run(spiral, 1.5, 12, 20000);
			assertTrue(spiral.done(), "reaches the middle, clockwise=" + clockwise);
			// the centre is 200 blocks to the side the player turns toward (south-facing: right is west, -X)
			double expectedCx = clockwise ? -200 : 200;
			assertEquals(expectedCx, flyer.x, 25, "ends near the centre, clockwise=" + clockwise);
			assertEquals(0, flyer.z, 25);
		}
	}

	@Test
	void spiralStillConvergesWhenMovementLagsTheFacing() {
		for (boolean clockwise : new boolean[]{true, false}) {
			for (double inertia : new double[]{0.5, 0.8}) {
				Flyer flyer = new Flyer(0);
				flyer.inertia = inertia;
				FlightPattern spiral = new FlightPattern.Spiral(0, 0, 0, clockwise, false, 200, 32, 6);
				flyer.run(spiral, 1.5, 12, 40000);
				assertTrue(spiral.done(), "reaches the middle, clockwise=" + clockwise + " inertia=" + inertia);
				assertTrue(Math.hypot(flyer.x - (clockwise ? -200 : 200), flyer.z) < 60, "ends near the centre: " + flyer.x + ", " + flyer.z);
			}
		}
	}

	@Test
	void spiralOutwardGrowsFromTheMiddleToTheRadius() {
		for (boolean clockwise : new boolean[]{true, false}) {
			Flyer flyer = new Flyer(0);
			flyer.inertia = 0.5;
			FlightPattern spiral = new FlightPattern.Spiral(0, 0, 0, clockwise, true, 200, 32, 20);
			flyer.run(spiral, 1.5, 12, 40000);
			assertTrue(spiral.done(), "reaches the outer radius, clockwise=" + clockwise);
			double cx = clockwise ? -20 : 20;
			assertEquals(200, Math.hypot(flyer.x - cx, flyer.z), 25, "ends on the outer lap: " + flyer.x + ", " + flyer.z);
			// it never went further out than the radius (plus a lap's worth of slack)
			assertTrue(Math.max(Math.max(flyer.maxX, -flyer.minX), Math.max(flyer.maxZ, -flyer.minZ)) < 260);
		}
	}

	@Test
	void pausedSpiralResumesOnTheSamePathFromWhereverYouAre() {
		for (boolean outward : new boolean[]{false, true}) {
			Flyer flyer = new Flyer(0);
			flyer.inertia = 0.5;
			FlightPattern spiral = new FlightPattern.Spiral(0, 0, 0, true, outward, 200, 32, 20);
			flyer.run(spiral, 1.5, 12, 500);
			assertTrue(!spiral.done());
			// landed 40 blocks off, repaired, flew back and switched it on again
			flyer.x += 40;
			flyer.z -= 25;
			spiral.resync(flyer.x, flyer.z);
			flyer.run(spiral, 1.5, 12, 40000);
			assertTrue(spiral.done(), "still finishes after the pause, outward=" + outward);
			double cx = -(outward ? 20 : 200);
			double end = Math.hypot(flyer.x - cx, flyer.z);
			assertTrue(outward ? end > 150 : end < 60, "ends where the original path ends, outward=" + outward + ": " + end);
		}
	}

	@Test
	void spiralKeepsTurningTheRightWay() {
		FlightPattern spiral = new FlightPattern.Spiral(0, 0, 0, true, false, 100, 16, 6);
		// at the start, clockwise means the yaw increases (a right turn)
		assertTrue(spiral.yaw(0, 0, 1.5) > 0, "right turn");
		FlightPattern left = new FlightPattern.Spiral(0, 0, 0, false, false, 100, 16, 6);
		assertTrue(left.yaw(0, 0, 1.5) < 0, "left turn");
	}

	@Test
	void squareSweepsTheWholeAreaInLanes() {
		Flyer flyer = new Flyer(0);
		FlightPattern square = new FlightPattern.Square(0, 0, 0, true, 200, 40);
		flyer.run(square, 1.5, 12, 20000);
		assertTrue(square.done(), "finishes the sweep");
		// facing south with a right (west) first turn: the square spans z 0..200 and x -200..0
		assertEquals(200, flyer.maxZ, 12);
		assertEquals(-200, flyer.minX, 12);
		assertTrue(flyer.maxX < 12 && flyer.minZ > -12, "stays inside the square");
	}

	@Test
	void orbitHoldsItsRadius() {
		FlightPattern.Orbit orbit = new FlightPattern.Orbit(0, 0, 0, true, 100);
		Flyer flyer = new Flyer(0);
		flyer.run(orbit, 1.5, 10, 2000);
		// Centre is 100 blocks to the right of a south-facing start: (-100, 0).
		double r = Math.hypot(flyer.x + 100, flyer.z);
		assertEquals(100, r, 8);
	}

	@Test
	void trailHeadsForDetectionsAheadAndIgnoresStrays() {
		FlightPattern.Trail trail = new FlightPattern.Trail(0, 60, 20, 200, 3);
		trail.points(java.util.List.of(new double[]{0, 100}, new double[]{-500, -10}));
		assertEquals(0, trail.yaw(0, 0, 1), 1e-3);
		trail.points(java.util.List.of(new double[]{-50, 50}));
		assertEquals(45, trail.yaw(0, 0, 1), 1e-3);
	}

	@Test
	void trailCirclesWhenLostThenGivesUp() {
		FlightPattern.Trail trail = new FlightPattern.Trail(0, 60, 2, 5, 3);
		for (int i = 0; i < 2; i++) trail.yaw(0, 0, 1);
		assertEquals(3, trail.yaw(0, 0, 1), 1e-3);
		for (int i = 0; i < 3; i++) trail.yaw(0, 0, 1);
		assertTrue(trail.done());
	}
}
