package dev.coffeeclient.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HolesTest {
	/** A floor and one layer of walls of {@code kind} everywhere, with open cells cut out at y = 0 and 1. */
	private static Holes.Blocks world(int kind, int[]... open) {
		Map<Long, Integer> openCells = new HashMap<>();
		for (int[] c : open) {
			openCells.put(key(c[0], 0, c[1]), Holes.OPEN);
			openCells.put(key(c[0], 1, c[1]), Holes.OPEN);
		}
		return (x, y, z) -> {
			if (y >= 2) return Holes.OPEN;
			if (y < 0) return kind;
			return openCells.getOrDefault(key(x, y, z), y == 1 ? Holes.OPEN : kind);
		};
	}

	private static long key(int x, int y, int z) {
		return ((long) x << 40) ^ ((long) y << 20) ^ z;
	}

	@Test
	void singleBedrockHole() {
		Holes.Hole h = Holes.find(world(Holes.BEDROCK, new int[]{0, 0}), 0, 0, 0, 2);
		assertEquals(Holes.Type.SINGLE, h.type());
		assertEquals(Holes.Safety.BEDROCK, h.safety());
	}

	@Test
	void doubleAndQuadAreFoundFromTheirNorthWestCell() {
		Holes.Blocks dbl = world(Holes.BLAST_PROOF, new int[]{0, 0}, new int[]{1, 0});
		assertEquals(Holes.Type.DOUBLE_X, Holes.find(dbl, 0, 0, 0, 2).type());
		assertNull(Holes.find(dbl, 1, 0, 0, 2));
		Holes.Blocks quad = world(Holes.SOLID, new int[]{0, 0}, new int[]{1, 0}, new int[]{0, 1}, new int[]{1, 1});
		Holes.Hole h = Holes.find(quad, 0, 0, 0, 2);
		assertEquals(Holes.Type.QUAD, h.type());
		assertEquals(Holes.Safety.UNSAFE, h.safety());
	}

	@Test
	void anOpenWallIsNoHole() {
		Holes.Blocks lShape = world(Holes.BEDROCK, new int[]{0, 0}, new int[]{1, 0}, new int[]{0, 1});
		assertNull(Holes.find(lShape, 0, 0, 0, 2));
	}
}
