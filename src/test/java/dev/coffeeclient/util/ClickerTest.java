package dev.coffeeclient.util;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickerTest {
	@Test
	void averagesTheTargetCps() {
		Clicker clicker = new Clicker(new Random(1));
		int clicks = 0;
		// 60 seconds of 50 ms ticks at 12 CPS.
		for (long now = 1; now <= 60_000; now += 50) clicks += clicker.due(now, 12, 0.3, 3);
		assertEquals(720, clicks, 30);
	}

	@Test
	void spreadVariesTheGapsAndZeroKeepsThemSteady() {
		Clicker clicker = new Clicker(new Random(2));
		long min = Long.MAX_VALUE, max = 0;
		for (int i = 0; i < 1000; i++) {
			long gap = clicker.gap(10, 0.3);
			min = Math.min(min, gap);
			max = Math.max(max, gap);
		}
		assertTrue(min < 70 && max > 150, min + ".." + max);
		assertEquals(100, clicker.gap(10, 0));
	}

	@Test
	void capsClicksPerTickAndDropsTheBacklog() {
		Clicker clicker = new Clicker(new Random(3));
		clicker.due(1, 20, 0, 1);
		// A 1 s stall owes 20 clicks; only the cap is paid, and the next tick starts fresh.
		assertEquals(2, clicker.due(1001, 20, 0, 2));
		assertEquals(0, clicker.due(1002, 20, 0, 2));
	}

	@Test
	void missChanceIsRoughlyRight() {
		Clicker clicker = new Clicker(new Random(4));
		int misses = 0;
		for (int i = 0; i < 10_000; i++) if (clicker.miss(0.2)) misses++;
		assertEquals(2000, misses, 150);
		assertTrue(!clicker.miss(0));
	}
}
