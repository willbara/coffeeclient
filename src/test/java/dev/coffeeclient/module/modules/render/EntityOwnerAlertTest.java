package dev.coffeeclient.module.modules.render;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityOwnerAlertTest {
	@Test
	void reportsOnceAndForgetsOnlyTheOldest() {
		Set<UUID> seen = new LinkedHashSet<>();
		UUID first = new UUID(0, 0);
		assertTrue(EntityOwner.markAlerted(seen, first));
		assertFalse(EntityOwner.markAlerted(seen, first));
		for (int i = 1; i <= 512; i++) EntityOwner.markAlerted(seen, new UUID(0, i));
		assertTrue(seen.size() <= 512);
		// Only the oldest was dropped, so a recent id is still not re-reported.
		assertFalse(EntityOwner.markAlerted(seen, new UUID(0, 512)));
		assertTrue(EntityOwner.markAlerted(seen, first));
	}
}
