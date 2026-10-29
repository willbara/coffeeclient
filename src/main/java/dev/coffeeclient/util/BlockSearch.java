package dev.coffeeclient.util;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Incremental block search over every loaded chunk, shared between modules that want the same blocks.
 *
 * <p>Each chunk is searched once, when it loads (or when a search starts), and then kept current from
 * block updates instead of being rescanned. The pipeline, with a palette skip in front:
 * <ol>
 *   <li>On the client thread, each section's palette is asked whether it can hold a match
 *       ({@link LevelChunkSection#maybeHas}); only sections that may match are copied.</li>
 *   <li>The copies are walked on one low-priority worker thread, nearest chunks first. The live chunk is
 *       never read off the client thread.</li>
 *   <li>Results come back to the client thread, where later block updates are applied to them.</li>
 * </ol>
 *
 * <p>Modules call {@link #acquire} with a key that identifies the blocks (equal keys share one search,
 * so BlockESP and X-Ray asking for the same set share the work) and {@link Handle#release} when done.
 * The match predicate runs on the worker thread, so it must be thread-safe (e.g. an immutable set
 * lookup). A chunk keeps at most {@link #MAX_PER_CHUNK} matches, which bounds memory when someone
 * searches for stone. All methods are client-thread only.
 */
public final class BlockSearch {
	public static final int MAX_PER_CHUNK = 2048;

	private static final Map<Object, Search> SEARCHES = new HashMap<>();
	/** Finished jobs, handed from the worker to the client thread. */
	private static final ConcurrentLinkedQueue<Job> DONE = new ConcurrentLinkedQueue<>();
	private static final AtomicLong SEQUENCE = new AtomicLong();
	private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
		new PriorityBlockingQueue<>(), r -> {
			Thread thread = new Thread(r, "Coffee block search");
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});

	static {
		WORKER.allowCoreThreadTimeOut(true);
	}

	private static ClientLevel level;
	private static EventBus bus;

	/**
	 * Attached only while some search runs: block updates fire for every client block change, and the
	 * chunk mixin skips building the event when nobody listens.
	 */
	private static final java.util.function.Consumer<Events.BlockUpdate> ON_BLOCK_UPDATE = e -> {
		if (Minecraft.getInstance().level != level) return;
		for (Search search : SEARCHES.values()) search.update(e.pos(), e.newState());
	};

	private BlockSearch() {
	}

	public static void init(EventBus eventBus) {
		bus = eventBus;
		bus.subscribe(Events.ChunkLoad.class, e -> {
			if (SEARCHES.isEmpty()) return;
			useLevel(e.level());
			for (Search search : SEARCHES.values()) search.submit(e.chunk());
		});
		bus.subscribe(Events.ChunkUnload.class, e -> {
			if (SEARCHES.isEmpty() || e.level() != level) return;
			long key = e.chunk().getPos().pack();
			for (Search search : SEARCHES.values()) search.drop(key);
		});
		bus.subscribe(Events.Tick.class, e -> drain());
		bus.subscribe(Events.GameLeft.class, e -> {
			for (Search search : SEARCHES.values()) search.clear();
			level = null;
		});
	}

	/**
	 * Starts (or joins) the search for {@code key}. A new search covers the chunks already loaded and
	 * then follows chunk loads and block updates.
	 */
	public static Handle acquire(Object key, Predicate<BlockState> match) {
		Search search = SEARCHES.get(key);
		if (search == null) {
			if (SEARCHES.isEmpty()) bus.subscribe(Events.BlockUpdate.class, EventBus.NORMAL, ON_BLOCK_UPDATE, "event:BlockUpdate:BlockSearch");
			search = new Search(match);
			SEARCHES.put(key, search);
			ClientLevel current = Minecraft.getInstance().level;
			if (current != null) {
				useLevel(current);
				search.submitLoaded(current);
			}
		}
		search.refs++;
		return new Handle(key, search);
	}

	/** Makes {@code current} the level being searched; switching levels clears every search. */
	private static void useLevel(ClientLevel current) {
		if (current == level) return;
		level = current;
		for (Search search : SEARCHES.values()) search.clear();
	}

	/** Applies finished worker results. Also called before results are read, so they are never a tick late. */
	private static void drain() {
		Job job;
		while ((job = DONE.poll()) != null) job.search.finish(job);
		ClientLevel current = Minecraft.getInstance().level;
		if (current != level && current != null && !SEARCHES.isEmpty()) {
			useLevel(current);
			for (Search search : SEARCHES.values()) search.submitLoaded(current);
		}
	}

	/** A module's share of a search. */
	public static final class Handle {
		private final Object key;
		private final Search search;
		private boolean released;

		private Handle(Object key, Search search) {
			this.key = key;
			this.search = search;
		}

		public Object key() {
			return key;
		}

		/** Changes whenever the results change, so callers can skip rebuilding. */
		public long version() {
			drain();
			return search.version;
		}

		/** Matches within {@code radius} blocks (a sphere) of {@code center}, up to {@code max} of them. */
		public List<BlockPos> collect(BlockPos center, int radius, int max) {
			drain();
			List<BlockPos> out = new ArrayList<>();
			long r2 = (long) radius * radius;
			int cx = center.getX() >> 4, cz = center.getZ() >> 4, cr = (radius >> 4) + 1;
			for (Long2ObjectMap.Entry<LongOpenHashSet> entry : search.byChunk.long2ObjectEntrySet()) {
				long chunk = entry.getLongKey();
				if (Math.abs(ChunkPos.getX(chunk) - cx) > cr || Math.abs(ChunkPos.getZ(chunk) - cz) > cr) continue;
				for (LongIterator it = entry.getValue().iterator(); it.hasNext(); ) {
					long packed = it.nextLong();
					long dx = BlockPos.getX(packed) - center.getX(), dy = BlockPos.getY(packed) - center.getY(), dz = BlockPos.getZ(packed) - center.getZ();
					if (dx * dx + dy * dy + dz * dz > r2) continue;
					out.add(BlockPos.of(packed));
					if (out.size() >= max) return out;
				}
			}
			return out;
		}

		/** True while some loaded chunks are still being searched. */
		public boolean searching() {
			drain();
			return !search.pending.isEmpty();
		}

		/** Stops sharing; the search ends when its last handle is released. Safe to call twice. */
		public void release() {
			if (released) return;
			released = true;
			if (--search.refs > 0) return;
			search.clear();
			SEARCHES.remove(key);
			if (SEARCHES.isEmpty()) bus.unsubscribe(Events.BlockUpdate.class, ON_BLOCK_UPDATE);
		}
	}

	private static final class Search {
		final Predicate<BlockState> match;
		/** Matches per chunk, as packed block positions. */
		final Long2ObjectOpenHashMap<LongOpenHashSet> byChunk = new Long2ObjectOpenHashMap<>();
		/** Chunks whose worker job has not come back yet. */
		final Long2ObjectOpenHashMap<Job> pending = new Long2ObjectOpenHashMap<>();
		int refs;
		long version;

		Search(Predicate<BlockState> match) {
			this.match = match;
		}

		void submitLoaded(ClientLevel level) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null) return;
			ChunkPos center = mc.player.chunkPosition();
			int r = mc.options.getEffectiveRenderDistance() + 2;
			for (int x = -r; x <= r; x++) {
				for (int z = -r; z <= r; z++) {
					LevelChunk chunk = level.getChunkSource().getChunk(center.x() + x, center.z() + z, ChunkStatus.FULL, false);
					if (chunk != null) submit(chunk);
				}
			}
		}

		/** Copies the sections that may hold a match and queues them for the worker (client thread). */
		void submit(LevelChunk chunk) {
			long key = chunk.getPos().pack();
			Job old = pending.remove(key);
			if (old != null) old.cancelled = true;

			LevelChunkSection[] sections = chunk.getSections();
			List<PalettedContainer<BlockState>> copies = new ArrayList<>();
			List<Integer> baseYs = new ArrayList<>();
			int minSectionY = chunk.getMinSectionY();
			for (int i = 0; i < sections.length; i++) {
				LevelChunkSection section = sections[i];
				if (section == null || section.hasOnlyAir() || !section.maybeHas(match)) continue;
				copies.add(section.getStates().copy());
				baseYs.add((minSectionY + i) << 4);
			}
			if (copies.isEmpty()) {
				if (byChunk.remove(key) != null) version++;
				return;
			}
			Job job = new Job(this, chunk.getPos(), copies, baseYs, distanceToPlayer(chunk.getPos()));
			pending.put(key, job);
			WORKER.execute(job);
		}

		void finish(Job job) {
			long key = job.chunk.pack();
			if (job.cancelled || pending.get(key) != job) return;
			pending.remove(key);
			LongOpenHashSet set = job.found.isEmpty() ? null : new LongOpenHashSet(job.found);
			// Block updates that arrived while the copy was being searched are newer: replay them.
			for (int i = 0; i < job.updates.size(); i++) {
				long pos = job.updates.getLong(i);
				boolean matches = job.updateMatches.get(i);
				if (matches) {
					if (set == null) set = new LongOpenHashSet();
					if (set.size() < MAX_PER_CHUNK) set.add(pos);
				} else if (set != null) {
					set.remove(pos);
				}
			}
			if (set == null || set.isEmpty()) byChunk.remove(key);
			else byChunk.put(key, set);
			version++;
		}

		void update(BlockPos pos, BlockState state) {
			long chunk = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
			boolean matches = match.test(state);
			long packed = pos.asLong();
			Job job = pending.get(chunk);
			if (job != null) {
				job.updates.add(packed);
				job.updateMatches.add(matches);
				return;
			}
			LongOpenHashSet set = byChunk.get(chunk);
			if (matches) {
				if (set == null) {
					set = new LongOpenHashSet();
					byChunk.put(chunk, set);
				}
				if (set.size() < MAX_PER_CHUNK && set.add(packed)) version++;
			} else if (set != null && set.remove(packed)) {
				if (set.isEmpty()) byChunk.remove(chunk);
				version++;
			}
		}

		void drop(long chunk) {
			Job job = pending.remove(chunk);
			if (job != null) job.cancelled = true;
			if (byChunk.remove(chunk) != null) version++;
		}

		void clear() {
			for (Job job : pending.values()) job.cancelled = true;
			pending.clear();
			if (!byChunk.isEmpty()) version++;
			byChunk.clear();
		}

		private static double distanceToPlayer(ChunkPos pos) {
			var player = Minecraft.getInstance().player;
			if (player == null) return 0;
			double dx = (pos.x() << 4) + 8 - player.getX(), dz = (pos.z() << 4) + 8 - player.getZ();
			return dx * dx + dz * dz;
		}
	}

	/** One chunk's copied sections, searched on the worker; nearest chunks run first. */
	private static final class Job implements Runnable, Comparable<Job> {
		final Search search;
		final ChunkPos chunk;
		final List<PalettedContainer<BlockState>> sections;
		final List<Integer> baseYs;
		final double distance;
		final long sequence = SEQUENCE.incrementAndGet();
		final LongArrayList found = new LongArrayList();
		/** Block updates for this chunk received while the job ran (client thread only). */
		final LongArrayList updates = new LongArrayList();
		final it.unimi.dsi.fastutil.booleans.BooleanArrayList updateMatches = new it.unimi.dsi.fastutil.booleans.BooleanArrayList();
		volatile boolean cancelled;

		Job(Search search, ChunkPos chunk, List<PalettedContainer<BlockState>> sections, List<Integer> baseYs, double distance) {
			this.search = search;
			this.chunk = chunk;
			this.sections = sections;
			this.baseYs = baseYs;
			this.distance = distance;
		}

		@Override
		public void run() {
			try {
				int baseX = chunk.getMinBlockX(), baseZ = chunk.getMinBlockZ();
				Predicate<BlockState> match = search.match;
				search:
				for (int s = 0; s < sections.size(); s++) {
					if (cancelled) return;
					PalettedContainer<BlockState> states = sections.get(s);
					int baseY = baseYs.get(s);
					for (int y = 0; y < 16; y++) {
						for (int z = 0; z < 16; z++) {
							for (int x = 0; x < 16; x++) {
								if (!match.test(states.get(x, y, z))) continue;
								found.add(BlockPos.asLong(baseX + x, baseY + y, baseZ + z));
								if (found.size() >= MAX_PER_CHUNK) break search;
							}
						}
					}
				}
			} catch (Throwable t) {
				ErrorGuard.report("block search", t);
				found.clear();
			}
			if (!cancelled) DONE.add(this);
		}

		@Override
		public int compareTo(Job other) {
			int c = Double.compare(distance, other.distance);
			return c != 0 ? c : Long.compare(sequence, other.sequence);
		}
	}
}
