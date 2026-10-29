package dev.coffeeclient.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TracersTest {
	@Test
	void flashPulsesBetweenAQuarterAndFull() {
		for (long t = 0; t < 3000; t += 37) {
			int alpha = Tracers.flash(0x80, t);
			assertTrue(alpha >= 0x80 / 4 && alpha <= 0x80, "alpha " + alpha + " at " + t);
		}
	}

	@Test
	void nearestOnlyKeepsTheClosest() {
		assertEquals(List.of(3), Tracers.nearestOnly(List.of(5, 3, 9), true, n -> n));
		assertEquals(List.of(5, 3, 9), Tracers.nearestOnly(List.of(5, 3, 9), false, n -> n));
		assertEquals(List.of(), Tracers.nearestOnly(List.<Integer>of(), true, n -> n));
	}
}
