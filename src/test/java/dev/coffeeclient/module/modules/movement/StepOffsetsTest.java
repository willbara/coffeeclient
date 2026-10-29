package dev.coffeeclient.module.modules.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StepOffsetsTest {
	@Test
	void outOfRangeHeightsHaveNoTable() {
		assertNull(Step.offsets(0.5, false));
		assertNull(Step.offsets(Double.NaN, false));
		assertNull(Step.offsets(2.6, false));
		assertNull(Step.offsets(1.5, true));
	}

	@Test
	void tallerStepsUseLongerTables() {
		assertEquals(2, Step.offsets(1, false).length);
		assertEquals(5, Step.offsets(1.5, false).length);
		assertEquals(8, Step.offsets(2, false).length);
		assertEquals(10, Step.offsets(2.5, false).length);
		assertEquals(3, Step.offsets(1, true).length);
	}
}
