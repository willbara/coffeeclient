package dev.coffeeclient.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScarecrowTest {
	@Test
	void ritualSaysWhatItShould() {
		assertEquals("Drink Coffee!", Scarecrow.ritual());
	}
}
