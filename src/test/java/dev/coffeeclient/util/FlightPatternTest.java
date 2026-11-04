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
	void squareSpiralWindsInwardInStraightLanes() {
		for (boolean clockwise : new boolean[]{true, false}) {
			for (double inertia : new double[]{0, 0.5}) {
				Flyer flyer = new Flyer(0);
				flyer.inertia = inertia;
				FlightPattern square = new FlightPattern.Square(0, 0, 0, clockwise, false, 200, 40, 20);
				flyer.run(square, 1.5, 12, 40000);
				String name = "clockwise=" + clockwise + " inertia=" + inertia;
				assertTrue(square.done(), "reaches the middle, " + name);
				// laps of 180, 140, 100, 60, 20 facing south, turning right (west) first: the outer lap spans
				// z 0..180 and x -180..0, and the innermost one ends at (-100, 80)
				double side = clockwise ? -1 : 1;
				assertEquals(180, flyer.maxZ, 12, name);
				assertEquals(180, clockwise ? -flyer.minX : flyer.maxX, 12, name);
				assertTrue((clockwise ? flyer.maxX : -flyer.minX) < 12 && flyer.minZ > -12, "stays inside the square, " + name);
				assertTrue(Math.hypot(flyer.x - side * 100, flyer.z - 80) < 15, "ends at the inner end: " + flyer.x + ", " + flyer.z);
			}
		}
	}

	@Test
	void squareSpiralFollowsTheLaneNotTheCorner() {
		// halfway down the first 180-block lane, 20 blocks off to the left of it: steer back toward the lane
		// (a right turn from south), not straight at the far corner
		FlightPattern square = new FlightPattern.Square(0, 0, 0, true, false, 200, 40, 20);
		float yaw = square.yaw(20, 90, 1.5);
		assertTrue(yaw > 45, "heads back to the lane: " + yaw);
	}

	@Test
	void squareSpiralOutwardGrowsFromTheMiddle() {
		for (boolean clockwise : new boolean[]{true, false}) {
			Flyer flyer = new Flyer(0);
			flyer.inertia = 0.5;
			FlightPattern square = new FlightPattern.Square(0, 0, 0, clockwise, true, 200, 40, 20);
			flyer.run(square, 1.5, 12, 40000);
			assertTrue(square.done(), "closes the outer lap, clockwise=" + clockwise);
			// laps of 20, 60, 100, 140, 180: x spans -100..80 (mirrored counterclockwise), z -80..100, ending at (-100, -80)
			double side = clockwise ? -1 : 1;
			assertTrue(Math.hypot(flyer.x - side * 100, flyer.z + 80) < 15, "ends on the outer corner: " + flyer.x + ", " + flyer.z);
			assertEquals(100, flyer.maxZ, 12);
			assertEquals(-80, flyer.minZ, 12);
		}
	}

	@Test
	void pausedSquareSpiralResumesOnTheNearestLane() {
		Flyer flyer = new Flyer(0);
		flyer.inertia = 0.5;
		FlightPattern square = new FlightPattern.Square(0, 0, 0, true, false, 200, 40, 20);
		flyer.run(square, 1.5, 12, 300);
		assertTrue(!square.done());
		flyer.x += 30;
		flyer.z -= 25;
		square.resync(flyer.x, flyer.z);
		flyer.run(square, 1.5, 12, 40000);
		assertTrue(square.done(), "still finishes after the pause");
		assertTrue(Math.hypot(flyer.x + 100, flyer.z - 80) < 15, "ends at the inner end: " + flyer.x + ", " + flyer.z);
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
		FlightPattern.Trail trail = new FlightPattern.Trail(0, 60, 20, 200, 3, 1, FlightPattern.Trail.Split.Straight);
		trail.points(java.util.List.of(new double[]{0, 100}, new double[]{-500, -10}));
		assertEquals(0, trail.yaw(0, 0, 1), 1e-3);
		trail.points(java.util.List.of(new double[]{-50, 50}));
		assertEquals(45, trail.yaw(0, 0, 1), 1e-3);
	}

	@Test
	void trailCirclesWhenLostThenGivesUp() {
		FlightPattern.Trail trail = new FlightPattern.Trail(0, 60, 2, 5, 3, 1, FlightPattern.Trail.Split.Straight);
		for (int i = 0; i < 2; i++) trail.yaw(0, 0, 1);
		assertEquals(3, trail.yaw(0, 0, 1), 1e-3);
		for (int i = 0; i < 3; i++) trail.yaw(0, 0, 1);
		assertTrue(trail.done());
	}
	@Test
	void trailWaitsForEnoughDetectionsBeforeSteering() {
		FlightPattern.Trail trail = new FlightPattern.Trail(0, 60, 2, 5, 3, 3, FlightPattern.Trail.Split.Straight);
		trail.points(java.util.List.of(new double[]{-50, 50, 1}, new double[]{-60, 60, 2}));
		for (int i = 0; i < 20; i++) assertEquals(0, trail.yaw(0, 0, 1), 1e-3, "holds the heading while searching");
		assertTrue(!trail.done(), "never gives up before locking on");
		trail.points(java.util.List.of(new double[]{-50, 50, 1}, new double[]{-60, 60, 2}, new double[]{-70, 70, 3}));
		assertEquals(45, trail.yaw(0, 0, 1), 1e-3);
		assertTrue(trail.locked());
	}

	@Test
	void trailSplitPreferenceFavoursItsSide() {
		// one detection 30 degrees right, one 30 degrees left: Right leans right of the middle
		double r = Math.tan(Math.toRadians(30)) * 100;
		java.util.List<double[]> fork = java.util.List.of(new double[]{r, 100, 1}, new double[]{-r, 100, 2});
		FlightPattern.Trail straight = new FlightPattern.Trail(0, 60, 20, 200, 3, 1, FlightPattern.Trail.Split.Straight);
		FlightPattern.Trail right = new FlightPattern.Trail(0, 60, 20, 200, 3, 1, FlightPattern.Trail.Split.Right);
		FlightPattern.Trail left = new FlightPattern.Trail(0, 60, 20, 200, 3, 1, FlightPattern.Trail.Split.Left);
		for (FlightPattern.Trail t : java.util.List.of(straight, right, left)) t.points(fork);
		float s = straight.yaw(0, 0, 1);
		assertTrue(right.yaw(0, 0, 1) > s, "right leans right");
		assertTrue(left.yaw(0, 0, 1) < s, "left leans left");
	}

	@Test
	void trailIgnoresAChunkDetectedAgain() {
		FlightPattern.Trail trail = new FlightPattern.Trail(0, 60, 20, 200, 3, 1, FlightPattern.Trail.Split.Straight);
		trail.points(java.util.List.of(new double[]{0, 100, 1}));
		assertEquals(0, trail.yaw(0, 0, 1), 1e-3);
		// the same chunk detected again later (reloaded) counts for nothing
		trail.points(java.util.List.of(new double[]{-50, 50, 5}, new double[]{0, 100, 9}));
		assertEquals(45, trail.yaw(0, 0, 1), 1e-3);
	}

	@Test
	void lawnmowerCoversTheRectangleInEvenLanes() {
		for (boolean alongX : new boolean[]{true, false}) {
			Flyer flyer = new Flyer(alongX ? -90 : 0);
			flyer.inertia = 0.3;
			FlightPattern mower = new FlightPattern.Lawnmower(0, 0, 300, 300, 64, alongX);
			double before = mower.remaining(0, 0);
			// 300 / 64 rounds up to 5 gaps of 60: 6 lanes of 300 and 5 crossings of 60
			assertEquals(6 * 300 + 5 * 60, before, 1e-6);
			flyer.run(mower, 1.5, 12, 60000);
			assertTrue(mower.done(), "finishes, alongX=" + alongX);
			assertTrue(flyer.maxX > 285 && flyer.maxZ > 285 && flyer.minX > -20 && flyer.minZ > -20, "covers the rectangle");
		}
	}

	@Test
	void gotoStopsAtTheDestination() {
		Flyer flyer = new Flyer(0);
		FlightPattern go = new FlightPattern.Goto(0, 0, -400, 250);
		double start = go.remaining(0, 0);
		assertEquals(Math.hypot(400, 250), start, 1e-6);
		flyer.run(go, 1.5, 12, 20000);
		assertTrue(go.done());
		assertTrue(Math.hypot(flyer.x + 400, flyer.z - 250) < 8, "ends at the destination: " + flyer.x + ", " + flyer.z);
	}

	@Test
	void remainingShrinksAlongTheSpiral() {
		FlightPattern spiral = new FlightPattern.Spiral(0, 0, 0, true, false, 200, 32, 6);
		Flyer flyer = new Flyer(0);
		double start = spiral.remaining(0, 0);
		flyer.run(spiral, 1.5, 12, 2000);
		assertTrue(spiral.remaining(flyer.x, flyer.z) < start - 1000, "about 3000 blocks flown");
		assertTrue(Double.isNaN(new FlightPattern.Orbit(0, 0, 0, true, 100).remaining(0, 0)));
	}
}
