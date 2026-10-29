package dev.coffeeclient.util;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvPacerTest {
	@Test
	void zeroDelayClicksRepeatedlyInOneTick() {
		InvPacer p = new InvPacer(new Random(1));
		assertTrue(p.ready(0));
		assertTrue(p.ready(0));
	}

	@Test
	void delayWaitsThatManyTicks() {
		InvPacer p = new InvPacer(new Random(1));
		assertTrue(p.ready(2));
		p.tick();
		assertFalse(p.ready(2));
		p.tick();
		assertTrue(p.ready(2));
	}

	@Test
	void startDelayHoldsTheFirstClick() {
		InvPacer p = new InvPacer(new Random(1));
		p.startDelay.set(1.0);
		p.start();
		assertFalse(p.ready(0));
		p.tick();
		assertTrue(p.ready(0));
	}

	@Test
	void missChanceSkipsSomeClicks() {
		InvPacer p = new InvPacer(new Random(1));
		p.missChance.set(50.0);
		int clicks = 0;
		for (int i = 0; i < 1000; i++) {
			p.tick();
			if (p.ready(0)) clicks++;
		}
		assertTrue(clicks > 0 && clicks < 1000);
	}
}
