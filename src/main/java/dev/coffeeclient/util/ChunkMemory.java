package dev.coffeeclient.util;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Chunks you have had loaded (keyed by {@code ChunkPos.pack}), each with when it was last seen, so
 * long-forgotten ones can be dropped. Plus {@link #nobodyNear}, for telling a sound or change that a
 * visible player explains from one nobody does.
 */
public final class ChunkMemory {
	private final Long2LongOpenHashMap seen = new Long2LongOpenHashMap();

	public void mark(long chunk, long now) {
		seen.put(chunk, now);
	}

	public boolean seen(long chunk) {
		return seen.containsKey(chunk);
	}

	/** Drops chunks not seen for {@code ms}. */
	public void forget(long ms, long now) {
		seen.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > ms);
	}

	public LongSet chunks() {
		return seen.keySet();
	}

	public int size() {
		return seen.size();
	}

	public void clear() {
		seen.clear();
	}

	/** True when no player but {@code except} is within {@code radius} of {@code pos}. */
	public static boolean nobodyNear(Level level, Vec3 pos, double radius, Entity except) {
		double r2 = radius * radius;
		for (Player p : level.players()) if (p != except && p.distanceToSqr(pos) <= r2) return false;
		return true;
	}
}
