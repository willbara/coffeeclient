package dev.coffeeclient.module.modules.movement.elytrafly.modes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConstantHeadroomTest {
	@Test
	void fromRestTheWholeCap() {
		assertEquals(2, Constant.headroom(0, 0, 1, 0, 2), 1e-9);
	}

	@Test
	void alongTheHeadingOnlyTheGap() {
		assertEquals(0.5, Constant.headroom(1.5, 0, 1, 0, 2), 1e-9);
	}

	@Test
	void pastTheCapNothing() {
		assertEquals(0, Constant.headroom(3, 0, 1, 0, 2), 1e-9);
	}
}
