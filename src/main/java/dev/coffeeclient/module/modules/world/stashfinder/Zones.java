package dev.coffeeclient.module.modules.world.stashfinder;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups chunks with detector finds into zones (chunks within {@code gap} of each other join up)
 * and scores them: each kind found in a chunk counts its weight once, plus a little for every
 * extra find of it, so one strong sign or several weak ones together make a zone worth a look.
 */
public final class Zones {
	public record Zone(Set<ChunkPos> chunks, double score, Set<Kind> kinds, ChunkPos first) {
		public double centerX() {
			return chunks.stream().mapToInt(ChunkPos::x).average().orElse(0) * 16 + 8;
		}

		public double centerZ() {
			return chunks.stream().mapToInt(ChunkPos::z).average().orElse(0) * 16 + 8;
		}
	}

	private Zones() {
	}

	/** How much one kind of find says about players having been here. */
	static double weight(Kind kind) {
		return switch (kind) {
			case MAN_MADE, HOLE -> 1;
			case TUNNEL, STAIR, COLUMN, TRAIL, AGE -> 2;
			case POCKET, SKY, ROOF -> 3;
		};
	}

	static double chunkScore(Map<Kind, List<AABB>> finds) {
		double score = 0;
		for (Map.Entry<Kind, List<AABB>> e : finds.entrySet()) {
			if (e.getValue().isEmpty()) continue;
			score += weight(e.getKey()) * (1 + 0.1 * Math.min(10, e.getValue().size() - 1));
		}
		return score;
	}

	public static List<Zone> build(Map<ChunkPos, Map<Kind, List<AABB>>> hits, int gap) {
		List<Zone> zones = new ArrayList<>();
		Set<ChunkPos> left = new HashSet<>(hits.keySet());
		while (!left.isEmpty()) {
			ChunkPos start = left.iterator().next();
			left.remove(start);
			Set<ChunkPos> chunks = new HashSet<>();
			ArrayDeque<ChunkPos> todo = new ArrayDeque<>(List.of(start));
			while (!todo.isEmpty()) {
				ChunkPos c = todo.poll();
				chunks.add(c);
				for (var it = left.iterator(); it.hasNext(); ) {
					ChunkPos o = it.next();
					if (Math.max(Math.abs(o.x() - c.x()), Math.abs(o.z() - c.z())) <= gap) {
						it.remove();
						todo.add(o);
					}
				}
			}
			double score = 0;
			Set<Kind> kinds = EnumSet.noneOf(Kind.class);
			ChunkPos first = start;
			for (ChunkPos c : chunks) {
				score += chunkScore(hits.get(c));
				kinds.addAll(hits.get(c).keySet());
				if (c.x() < first.x() || c.x() == first.x() && c.z() < first.z()) first = c;
			}
			zones.add(new Zone(chunks, score, kinds, first));
		}
		return zones;
	}
}
