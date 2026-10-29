package dev.coffeeclient.util;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoveSimTest {
	@Test
	void gravityThenDrag() {
		Vec3 v = MoveSim.step(new Vec3(0, 0.42, 0), false);
		assertEquals((0.42 - 0.08) * 0.98, v.y, 1e-9);
	}

	@Test
	void airFrictionUnlessKept() {
		assertEquals(0.91, MoveSim.step(new Vec3(1, 0, 0), false).x, 1e-9);
		assertEquals(1, MoveSim.step(new Vec3(1, 0, 0), true).x, 1e-9);
	}

	@Test
	void fallDamageStartsAfterThreeBlocks() {
		assertEquals(0, MoveSim.fallDamage(3));
		assertEquals(1, MoveSim.fallDamage(3.5));
		assertEquals(20, MoveSim.fallDamage(23));
	}
}
