package dev.coffeeclient.util;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FootprintTest {
	/** Flat ground: solid at y <= 63. */
	private static final Holes.Blocks GROUND = (x, y, z) -> y <= 63 ? Holes.SOLID : Holes.OPEN;

	private static AABB player(double x, double y, double z) {
		return new AABB(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
	}

	@Test
	void centredPlayerHasOneColumnAndFourWalls() {
		LongSet columns = Footprint.columns(player(0.5, 64, 0.5), GROUND);
		assertEquals(1, columns.size());
		List<BlockPos> ring = Footprint.ring(columns);
		assertEquals(4, ring.size());
		assertTrue(ring.contains(new BlockPos(1, 64, 0)) && ring.contains(new BlockPos(0, 64, -1)));
	}

	@Test
	void playerOnAnEdgeOrCornerNeedsMoreWalls() {
		assertEquals(6, Footprint.ring(Footprint.columns(player(1.0, 64, 0.5), GROUND)).size());
		assertEquals(8, Footprint.ring(Footprint.columns(player(1.0, 64, 1.0), GROUND)).size());
	}

	@Test
	void levelIsWhereTheBoxRestsEvenMidJumpOrJustAbove() {
		assertEquals(64, Footprint.level(player(0.5, 64, 0.5), GROUND));
		assertEquals(64, Footprint.level(player(0.5, 65.1, 0.5), GROUND));
		// More than the drop allowance up: the box's own level.
		assertEquals(70, Footprint.level(player(0.5, 70, 0.5), GROUND));
	}
}
