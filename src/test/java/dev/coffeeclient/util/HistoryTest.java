package dev.coffeeclient.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HistoryTest {
	@Test
	void newestFirstAndCapped() {
		for (int i = 0; i < 2100; i++) History.add("Mod", "line " + i);
		List<History.Entry> lines = History.newestFirst();
		assertEquals(2000, lines.size());
		assertEquals("line 2099", lines.get(0).text());
		assertEquals("line 100", lines.get(lines.size() - 1).text());
	}
}
