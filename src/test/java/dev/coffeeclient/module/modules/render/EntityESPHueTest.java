package dev.coffeeclient.module.modules.render;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityESPHueTest {
	@Test
	void uniqueHueIsStableAndInTheCircle() {
		UUID player = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
		UUID other = UUID.fromString("00000000-0000-0000-0000-000000000001");
		assertEquals(EntityESP.uniqueHue(player), EntityESP.uniqueHue(player));
		for (UUID id : new UUID[] {player, other}) {
			assertTrue(EntityESP.uniqueHue(id) >= 0 && EntityESP.uniqueHue(id) < 360, "hue " + EntityESP.uniqueHue(id));
		}
	}
}
