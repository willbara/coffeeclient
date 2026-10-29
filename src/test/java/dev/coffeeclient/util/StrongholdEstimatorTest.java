package dev.coffeeclient.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrongholdEstimatorTest {
	private static final long SEED = 0x51F15EEDL;

	@Test
	void yawFollowsMinecraft() {
		assertEquals(0, StrongholdEstimator.yaw(0, 1), 1e-9, "south");
		assertEquals(90, StrongholdEstimator.yaw(-1, 0), 1e-9, "west");
		assertEquals(-90, StrongholdEstimator.yaw(1, 0), 1e-9, "east");
		assertEquals(180, Math.abs(StrongholdEstimator.yaw(0, -1)), 1e-9, "north");
	}

	@Test
	void firstRingSitsAroundTwoThousandBlocks() {
		int[] xs = new int[StrongholdEstimator.STRONGHOLDS], zs = new int[StrongholdEstimator.STRONGHOLDS];
		StrongholdEstimator.layout(SEED, xs, zs);
		for (int i = 0; i < 3; i++) {
			double blocks = Math.hypot(xs[i], zs[i]) * 16;
			assertTrue(blocks > 1280 && blocks < 2900, "first ring stronghold at " + (int) blocks + " blocks");
		}
	}

	/** Two exact throws at a stronghold of one of the layouts find that stronghold's chunk. */
	@Test
	void twoThrowsFindTheStronghold() {
		int[] xs = new int[StrongholdEstimator.STRONGHOLDS], zs = new int[StrongholdEstimator.STRONGHOLDS];
		StrongholdEstimator.layout(SEED + 7, xs, zs);
		double ax = 300, az = -200, bx = 600, bz = 150;
		int target = StrongholdEstimator.nearest(xs, zs, ax, az);
		assertEquals(target, StrongholdEstimator.nearest(xs, zs, bx, bz), "both throws see the same stronghold");
		double tx = xs[target] * 16 + 8, tz = zs[target] * 16 + 8;
		List<StrongholdEstimator.Throw> throwsList = List.of(
			new StrongholdEstimator.Throw(ax, az, StrongholdEstimator.yaw(tx - ax, tz - az)),
			new StrongholdEstimator.Throw(bx, bz, StrongholdEstimator.yaw(tx - bx, tz - bz)));

		StrongholdEstimator.Result result = StrongholdEstimator.estimate(throwsList, 2000, 0.03, true, 3);
		assertNotNull(result);
		StrongholdEstimator.Candidate best = result.best();
		assertEquals(xs[target], best.chunkX());
		assertEquals(zs[target], best.chunkZ());
		assertTrue(best.probability() > 0.5, "confident: " + best.probability());
	}

	@Test
	void noThrowsNoAnswer() {
		assertNull(StrongholdEstimator.estimate(List.of(), 100, 0.03, true, 3));
	}
}
