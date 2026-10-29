package dev.coffeeclient.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SpawnerMarksTest {
	@Test
	void clockShowsMinutesAndSeconds() {
		assertEquals("0:00", SpawnerMarks.clock(0));
		assertEquals("0:59", SpawnerMarks.clock(59 * 20 + 19));
		assertEquals("30:00", SpawnerMarks.clock(36000));
	}
}
