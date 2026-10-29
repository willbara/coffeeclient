package dev.coffeeclient.module.modules.world.stashfinder;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lines of dropped items underground, left behind by someone mining (a full inventory spills
 * cobblestone along their tunnel). After MiningEvidence, simplified: drops chain
 * together when close, and each chain is drawn as a line from one end to the other.
 */
public final class MiningTrails {
	/** Fresh drops are too often your own or a mob's. */
	private static final int MIN_AGE_TICKS = 60;
	/** Drops this close to you, or to a block you broke recently, are yours. */
	private static final double OWN_RADIUS = 12, OWN_BREAK_RADIUS = 5;
	private static final long OWN_BREAK_MS = 60_000;

	public record Trail(String dimension, List<Vec3> points, long seenAt) {
	}

	private record Break(BlockPos pos, long at) {
	}

	private final Map<String, Trail> trails = new LinkedHashMap<>();
	private final Deque<Break> breaks = new ArrayDeque<>();

	void clear() {
		trails.clear();
		breaks.clear();
	}

	void onBreak(BlockPos pos) {
		breaks.addLast(new Break(pos.immutable(), System.currentTimeMillis()));
		while (breaks.size() > 256) breaks.removeFirst();
	}

	List<Trail> trails(String dimension) {
		List<Trail> out = new ArrayList<>();
		for (Trail t : trails.values()) if (t.dimension().equals(dimension)) out.add(t);
		return out;
	}

	/** Returns the trails found this pass that weren't known before. */
	List<Trail> update(ClientLevel level, Player player, double scanDistance, int maxY, double joinDistance, int minDrops, int lifetimeMinutes) {
		long now = System.currentTimeMillis();
		breaks.removeIf(b -> now - b.at() > OWN_BREAK_MS);
		if (lifetimeMinutes > 0) trails.values().removeIf(t -> now - t.seenAt() > lifetimeMinutes * 60_000L);

		List<Vec3> drops = new ArrayList<>();
		for (Entity e : level.entitiesForRendering()) {
			if (!(e instanceof ItemEntity item) || item.tickCount < MIN_AGE_TICKS || item.getY() > maxY) continue;
			double d = item.distanceTo(player);
			if (d > scanDistance || d < OWN_RADIUS || nearOwnBreak(item.position())) continue;
			drops.add(item.position());
		}

		String dimension = level.dimension().identifier().toString();
		List<Trail> fresh = new ArrayList<>();
		for (List<Vec3> cluster : clusters(drops, joinDistance)) {
			if (cluster.size() < minDrops) continue;
			List<Vec3> line = order(cluster);
			String key = dimension + "|" + quantise(line.getFirst()) + "|" + quantise(line.getLast());
			String reverse = dimension + "|" + quantise(line.getLast()) + "|" + quantise(line.getFirst());
			boolean known = trails.containsKey(key) || trails.containsKey(reverse);
			trails.remove(reverse);
			Trail trail = new Trail(dimension, line, now);
			trails.put(key, trail);
			if (!known) fresh.add(trail);
		}
		return fresh;
	}

	private boolean nearOwnBreak(Vec3 pos) {
		for (Break b : breaks) if (Vec3.atCenterOf(b.pos()).distanceToSqr(pos) <= OWN_BREAK_RADIUS * OWN_BREAK_RADIUS) return true;
		return false;
	}

	/** Groups drops that chain together within the join distance. */
	private static List<List<Vec3>> clusters(List<Vec3> drops, double join) {
		List<List<Vec3>> out = new ArrayList<>();
		boolean[] used = new boolean[drops.size()];
		double join2 = join * join;
		for (int i = 0; i < drops.size(); i++) {
			if (used[i]) continue;
			List<Vec3> cluster = new ArrayList<>();
			ArrayDeque<Integer> queue = new ArrayDeque<>();
			queue.add(i);
			used[i] = true;
			while (!queue.isEmpty()) {
				Vec3 p = drops.get(queue.removeFirst());
				cluster.add(p);
				for (int j = 0; j < drops.size(); j++) {
					if (!used[j] && drops.get(j).distanceToSqr(p) <= join2) {
						used[j] = true;
						queue.add(j);
					}
				}
			}
			out.add(cluster);
		}
		return out;
	}

	/** Orders a cluster along the line between its two farthest drops. */
	private static List<Vec3> order(List<Vec3> cluster) {
		Vec3 a = cluster.getFirst(), b = a;
		double best = -1;
		for (Vec3 p : cluster) {
			for (Vec3 q : cluster) {
				double d = p.distanceToSqr(q);
				if (d > best) {
					best = d;
					a = p;
					b = q;
				}
			}
		}
		Vec3 start = a, axis = b.subtract(a);
		List<Vec3> line = new ArrayList<>(cluster);
		line.sort(Comparator.comparingDouble(p -> p.subtract(start).dot(axis)));
		return line;
	}

	private static String quantise(Vec3 p) {
		return ((int) Math.floor(p.x) >> 2) + "," + ((int) Math.floor(p.y) >> 2) + "," + ((int) Math.floor(p.z) >> 2);
	}
}
