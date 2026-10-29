package dev.coffeeclient.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PidTest {
	@Test
	void proportionalAndIntegralOnTheFirstStep() {
		assertEquals(10 * 1.2 + 10 * 0.04, new Pid(1.2, 0.04, 0.85).step(10, 0), 1e-9);
	}

	@Test
	void derivativeFollowsTheChangeInError() {
		Pid pid = new Pid(0, 0, 1);
		pid.step(10, 0);
		assertEquals(-4, pid.step(10, 4), 1e-9);
	}

	@Test
	void integralIsClamped() {
		Pid pid = new Pid(0, 1, 0);
		for (int k = 0; k < 50; k++) pid.step(10, 0);
		assertEquals(100, pid.step(10, 0), 1e-9);
	}
}
