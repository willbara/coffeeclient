package dev.coffeeclient.module.modules.world;

import dev.coffeeclient.module.modules.world.AutoBuild.Layout;
import dev.coffeeclient.module.modules.world.AutoBuild.Preset;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoBuildTest {
	private static final BlockPos FEET = new BlockPos(0, 64, 0);

	@Test
	void portalFrameIsTenCellsWithoutCornersTwoAhead() {
		// Facing north (-Z): right is east (+X); the opening is x 0..1, y 65..67, at z -2.
		Layout layout = AutoBuild.layout(Preset.PORTAL, FEET, Direction.NORTH);
		List<BlockPos> frame = layout.body();
		assertEquals(10, new HashSet<>(frame).size());
		assertTrue(frame.stream().allMatch(p -> p.getZ() == -2));
		assertEquals(new BlockPos(0, 64, -2), frame.get(0));
		for (int y = 65; y <= 67; y++) {
			assertTrue(frame.contains(new BlockPos(-1, y, -2)) && frame.contains(new BlockPos(2, y, -2)));
			assertTrue(layout.clear().contains(new BlockPos(0, y, -2)) && layout.clear().contains(new BlockPos(1, y, -2)));
		}
		assertEquals(new BlockPos(0, 65, -2), layout.clear().get(0));
		assertTrue(frame.contains(new BlockPos(0, 68, -2)) && frame.contains(new BlockPos(1, 68, -2)));
		assertFalse(frame.contains(new BlockPos(-1, 64, -2)) || frame.contains(new BlockPos(2, 68, -2)));
		assertTrue(layout.heads().isEmpty());
		assertBottomUp(frame);
	}

	@Test
	void witherIsATWithThreeSkullsOnTop() {
		Layout layout = AutoBuild.layout(Preset.WITHER, FEET, Direction.NORTH);
		assertEquals(List.of(new BlockPos(0, 64, -3), new BlockPos(0, 65, -3), new BlockPos(-1, 65, -3), new BlockPos(1, 65, -3)), layout.body());
		// Every skull sits on a body block.
		assertTrue(layout.heads().stream().allMatch(h -> h.getY() == 66 && layout.body().contains(h.below())));
		assertEquals(3, new HashSet<>(layout.heads()).size());
		assertEquals(List.of(new BlockPos(-1, 64, -3), new BlockPos(1, 64, -3)), layout.clear());
		assertBottomUp(layout.body());
	}

	@Test
	void golemsHaveOnePumpkinOnTheStem() {
		Layout iron = AutoBuild.layout(Preset.IRON_GOLEM, FEET, Direction.EAST);
		assertEquals(List.of(new BlockPos(3, 66, 0)), iron.heads());
		assertEquals(4, iron.clear().size());
		Layout snow = AutoBuild.layout(Preset.SNOW_GOLEM, FEET, Direction.EAST);
		assertEquals(List.of(new BlockPos(3, 64, 0), new BlockPos(3, 65, 0)), snow.body());
		assertEquals(List.of(new BlockPos(3, 66, 0)), snow.heads());
	}

	private static void assertBottomUp(List<BlockPos> cells) {
		for (int i = 1; i < cells.size(); i++) assertTrue(cells.get(i).getY() >= cells.get(i - 1).getY());
	}
}
