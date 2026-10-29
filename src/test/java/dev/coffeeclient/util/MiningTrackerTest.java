package dev.coffeeclient.util;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningTrackerTest {
	private static final BlockPos A = new BlockPos(0, 64, 0), B = new BlockPos(1, 64, 0);

	@Test
	void oneBreakPerBreakerKeepingItsStart() {
		MiningTracker t = new MiningTracker();
		t.progress(7, A, 1, 100);
		t.progress(7, A, 5, 400);
		assertEquals(1, t.active().size());
		assertEquals(100, t.active().getFirst().startedAt());
		assertEquals(5, t.active().getFirst().progress());
	}

	@Test
	void aBlockLeftBehindIsStillAttributed() {
		MiningTracker t = new MiningTracker();
		t.progress(7, A, 8, 100);
		t.progress(7, B, 0, 200);
		MiningTracker.Broken broken = t.blockGone(A, 300);
		assertEquals(7, broken.breaker());
		assertFalse(broken.current());
		assertTrue(t.blockGone(B, 300).current());
	}

	@Test
	void forgetsAfterExpiry() {
		MiningTracker t = new MiningTracker();
		t.progress(7, A, 3, 0);
		t.prune(MiningTracker.EXPIRE_MS + 1);
		assertTrue(t.active().isEmpty());
		assertNull(t.blockGone(A, MiningTracker.EXPIRE_MS + 1));
	}
}
