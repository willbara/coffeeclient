package dev.coffeeclient.module.modules.movement.elytrafly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.coffeeclient.module.modules.movement.elytrafly.FireworkUse.Trigger;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class FireworkUseTest {
	@Test
	void triggersPickTimerSpeedOrEither() {
		assertTrue(FireworkUse.due(Trigger.Timer, 160, 160, 50, 25));
		assertFalse(FireworkUse.due(Trigger.Timer, 10, 160, 5, 25));
		assertTrue(FireworkUse.due(Trigger.Speed, 0, 160, 5, 25));
		assertFalse(FireworkUse.due(Trigger.Speed, 999, 160, 30, 25));
		assertTrue(FireworkUse.due(Trigger.Either, 160, 160, 50, 25));
		assertTrue(FireworkUse.due(Trigger.Either, 0, 160, 5, 25));
		assertFalse(FireworkUse.due(Trigger.Either, 0, 160, 50, 25));
	}

	@Test
	void boostScalesAxesAndCapsTheResult() {
		Vec3 push = new Vec3(1, 2, 3);
		assertEquals(new Vec3(2, 1, 6), FireworkUse.boost(Vec3.ZERO, push, 2, 0.5, 0));

		Vec3 capped = FireworkUse.boost(new Vec3(1, 0, 0), new Vec3(10, 0, 0), 1, 1, 2);
		assertEquals(2.0, new Vec3(1, 0, 0).add(capped).length(), 1e-9);
		assertEquals(push, FireworkUse.boost(Vec3.ZERO, push, 1, 1, 100));
	}

	@Test
	void boostRaisesTopSpeedAndStaysStable() {
		Vec3 look = new Vec3(1, 0, 0);
		for (double h : new double[] {1, 3, 5}) {
			Vec3 velocity = Vec3.ZERO;
			for (int tick = 0; tick < 200; tick++) {
				Vec3 vanilla = look.scale(0.85).subtract(velocity.scale(0.5));
				velocity = velocity.add(FireworkUse.boost(velocity, vanilla, h, h, 0));
			}
			assertEquals(1.7 * h, velocity.x, 1e-6);
		}
	}
}
