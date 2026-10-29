package dev.coffeeclient.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiningManagerTest {
	@Test
	void diamondPickaxeOnStone() {
		// Speed 8 on hardness 1.5 with the right tool: 8 / 1.5 / 30 per tick, so 6 ticks.
		double perTick = MiningManager.progress(8, true, 1.5f, -1, -1, 1, 1, true);
		assertEquals(8 / 1.5 / 30, perTick, 1e-9);
		assertEquals(6, (int) Math.ceil(1 / perTick));
	}

	@Test
	void airAndWaterAndHaste() {
		double ground = MiningManager.progress(8, true, 1.5f, -1, -1, 1, 1, true);
		assertEquals(ground / 5, MiningManager.progress(8, true, 1.5f, -1, -1, 1, 1, false), 1e-9);
		assertEquals(ground * 0.2, MiningManager.progress(8, true, 1.5f, -1, -1, 1, 0.2, true), 1e-9);
		assertEquals(ground * 1.4, MiningManager.progress(8, true, 1.5f, 1, -1, 1, 1, true), 1e-9);
	}

	@Test
	void unbreakableAndInstant() {
		assertEquals(0, MiningManager.progress(8, true, -1, -1, -1, 1, 1, true));
		assertEquals(1, MiningManager.progress(1, false, 0, -1, -1, 1, 1, true));
	}
}
