package dev.coffeeclient.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BallisticsTest {
	/** A full-draw arrow: 3 blocks per tick, gravity 0.05, drag 0.99. */
	private static final double SPEED = 3, GRAVITY = 0.05, DRAG = 0.99;

	@Test
	void solvedElevationHitsTheTarget() {
		for (double distance : new double[]{5, 20, 45}) {
			for (double height : new double[]{-8, 0, 6}) {
				double elevation = Ballistics.elevation(distance, height, SPEED, GRAVITY, DRAG);
				Ballistics.Arrival arrival = Ballistics.arrive(elevation, distance, SPEED, GRAVITY, DRAG);
				assertEquals(height, arrival.height(), 0.05, distance + " out, " + height + " up");
			}
		}
	}

	@Test
	void farTargetsAimAboveTheLineAndOutOfReachIsNaN() {
		assertTrue(Ballistics.elevation(40, 0, SPEED, GRAVITY, DRAG) > 0);
		assertTrue(Double.isNaN(Ballistics.elevation(40, 200, SPEED, GRAVITY, DRAG)));
	}
}
