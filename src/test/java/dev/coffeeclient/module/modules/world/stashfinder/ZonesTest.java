package dev.coffeeclient.module.modules.world.stashfinder;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ZonesTest {
	private static final AABB BOX = new AABB(0, 0, 0, 1, 1, 1);

	@Test
	void nearbyChunksMergeAndScoresAdd() {
		Map<ChunkPos, Map<Kind, List<AABB>>> hits = new HashMap<>();
		hits.put(new ChunkPos(0, 0), Map.of(Kind.TUNNEL, List.of(BOX)));
		hits.put(new ChunkPos(1, 1), Map.of(Kind.POCKET, List.of(BOX, BOX)));
		hits.put(new ChunkPos(10, 10), Map.of(Kind.HOLE, List.of(BOX)));
		List<Zones.Zone> zones = Zones.build(hits, 1);
		assertEquals(2, zones.size());
		Zones.Zone big = zones.stream().filter(z -> z.chunks().size() == 2).findFirst().orElseThrow();
		assertEquals(2 + 3 * 1.1, big.score(), 1e-9);
		assertEquals(new ChunkPos(0, 0), big.first());
	}

	@Test
	void gapZeroKeepsChunksApart() {
		Map<ChunkPos, Map<Kind, List<AABB>>> hits = new HashMap<>();
		hits.put(new ChunkPos(0, 0), Map.of(Kind.HOLE, List.of(BOX)));
		hits.put(new ChunkPos(1, 0), Map.of(Kind.HOLE, List.of(BOX)));
		assertEquals(2, Zones.build(hits, 0).size());
	}
}
