package dev.coffeeclient.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkMemoryTest {
	@Test
	void forgetsOnlyStaleChunks() {
		ChunkMemory m = new ChunkMemory();
		m.mark(1, 0);
		m.mark(2, 5000);
		m.forget(3000, 6000);
		assertFalse(m.seen(1));
		assertTrue(m.seen(2));
		assertEquals(1, m.size());
	}

	@Test
	void markingAgainRefreshes() {
		ChunkMemory m = new ChunkMemory();
		m.mark(1, 0);
		m.mark(1, 5000);
		m.forget(3000, 6000);
		assertTrue(m.seen(1));
	}
}
