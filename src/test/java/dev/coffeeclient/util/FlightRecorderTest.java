package dev.coffeeclient.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlightRecorderTest {
	@Test
	void summarisesKeepAliveGaps() {
		assertEquals("Keep-alives: 3, average gap 15.0 s, longest 20.0 s, last 5.0 s before the end. Setbacks: 2.",
			FlightRecorder.summary(List.of(0L, 10_000L, 30_000L), 35_000L, 2));
	}

	@Test
	void noKeepAlives() {
		assertEquals("Keep-alives: 0. Setbacks: 0.", FlightRecorder.summary(List.of(), 1000L, 0));
	}
}
