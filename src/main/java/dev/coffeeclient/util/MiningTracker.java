package dev.coffeeclient.util;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Who is breaking what, from the server's block-destruction packets: one active break per breaker
 * (as vanilla keeps them), forgotten after {@link #EXPIRE_MS} without an update, and the last few
 * seconds of breaks kept so a block that vanishes after its breaker moved on is still attributed.
 */
public final class MiningTracker {
	public static final long EXPIRE_MS = 20_000;
	private static final long RECENT_MS = 3_000;

	public record Entry(int breaker, BlockPos pos, int progress, long startedAt, long updatedAt) {
	}

	/** A tracked block that turned to air; {@code current} is false when its breaker had moved on to another block. */
	public record Broken(int breaker, BlockPos pos, long at, boolean current) {
	}

	private final Map<Integer, Entry> active = new HashMap<>();
	private final List<Entry> recent = new ArrayList<>();

	/** A destruction packet: {@code progress} 0-9 is a stage, anything else ends the break. */
	public void progress(int breaker, BlockPos pos, int progress, long now) {
		Entry old = active.get(breaker);
		if (progress < 0 || progress > 9) {
			if (old != null) recent.add(active.remove(breaker));
			return;
		}
		if (old != null && !old.pos().equals(pos)) recent.add(old);
		long started = old != null && old.pos().equals(pos) ? old.startedAt() : now;
		active.put(breaker, new Entry(breaker, pos.immutable(), progress, started, now));
	}

	/** A block became air: who was (or just had been) breaking it, else null. */
	public Broken blockGone(BlockPos pos, long now) {
		for (Entry e : active.values()) if (e.pos().equals(pos)) return new Broken(e.breaker(), e.pos(), now, true);
		for (int i = recent.size() - 1; i >= 0; i--) {
			Entry e = recent.get(i);
			if (e.pos().equals(pos) && now - e.updatedAt() <= RECENT_MS) return new Broken(e.breaker(), e.pos(), now, false);
		}
		return null;
	}

	public void prune(long now) {
		active.values().removeIf(e -> now - e.updatedAt() > EXPIRE_MS);
		recent.removeIf(e -> now - e.updatedAt() > RECENT_MS);
	}

	public List<Entry> active() {
		return List.copyOf(active.values());
	}

	public void clear() {
		active.clear();
		recent.clear();
	}
}
