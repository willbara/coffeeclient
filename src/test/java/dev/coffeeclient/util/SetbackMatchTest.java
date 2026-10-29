package dev.coffeeclient.util;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SetbackMatchTest {
	@Test
	void countsTicksBackFromTheNewest() {
		List<Vec3> sent = List.of(new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(2, 0, 0), new Vec3(3, 0, 0));
		double[] m = Setback.match(sent, new Vec3(1.1, 0, 0));
		assertEquals(2, (int) m[0]);
		assertEquals(0.1, m[1], 1e-9);
	}

	@Test
	void emptyHistoryIsZero() {
		double[] m = Setback.match(List.of(), new Vec3(5, 5, 5));
		assertEquals(0, (int) m[0]);
	}
}
