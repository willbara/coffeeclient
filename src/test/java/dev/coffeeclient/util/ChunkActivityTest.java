package dev.coffeeclient.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkActivityTest {
	@Test
	void scoreHalvesEveryHalfWindow() {
		assertEquals(20, ChunkActivity.decay(40, 4000, 8), 1e-9);
	}

	@Test
	void levelsFollowThresholds() {
		assertEquals(-1, ChunkActivity.level(9.9));
		assertEquals(0, ChunkActivity.level(10));
		assertEquals(1, ChunkActivity.level(45));
		assertEquals(2, ChunkActivity.level(70));
	}

	@Test
	void ringStartsTwoChunksPastSimulationAndEndsAtView() {
		assertFalse(ChunkActivity.inOuterRing(11, 10, 16));
		assertTrue(ChunkActivity.inOuterRing(12, 10, 16));
		assertTrue(ChunkActivity.inOuterRing(16, 10, 16));
		assertFalse(ChunkActivity.inOuterRing(17, 10, 16));
		assertFalse(ChunkActivity.inOuterRing(12, 0, 16));
	}
}
