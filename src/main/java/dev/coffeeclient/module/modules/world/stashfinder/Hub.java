package dev.coffeeclient.module.modules.world.stashfinder;

import dev.coffeeclient.module.modules.world.StashFinder;
import dev.coffeeclient.util.BlockIds;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Runs StashFinder's block detectors chunk by chunk on the client thread, nearest chunk first, within
 * a time budget per tick. Chunks are scanned when they load, again when their blocks change, and from
 * scratch when a detector setting changes. With a sticky area, finds stay after their chunk unloads.
 */
public final class Hub {
	/** Something a scan found that its chunk didn't have before. */
	public record Found(Kind kind, AABB box) {
	}

	private final StashFinder sf;
	private final Map<ChunkPos, Map<Kind, List<AABB>>> hits = new HashMap<>();
	private final Set<ChunkPos> queue = new LinkedHashSet<>();
	private final Set<ChunkPos> scanned = new HashSet<>();
	private final Set<ChunkPos> dirty = new HashSet<>();
	private final MiningTrails trails = new MiningTrails();
	private ChunkPos lastCenter;
	private long version;
	private int ticks;
	/** Built from the settings on the first scan after a change. */
	private Predicate<BlockState> manMade;

	public Hub(StashFinder sf) {
		this.sf = sf;
	}

	public long version() {
		return version;
	}

	public Map<ChunkPos, Map<Kind, List<AABB>>> hits() {
		return hits;
	}

	public List<MiningTrails.Trail> trails(String dimension) {
		return trails.trails(dimension);
	}

	/** Forget everything (dimension change, disable). */
	public void clear() {
		hits.clear();
		queue.clear();
		scanned.clear();
		dirty.clear();
		trails.clear();
		lastCenter = null;
		manMade = null;
		version++;
	}

	/** A detector setting changed: drop the finds and scan the area again. */
	public void rescan() {
		hits.clear();
		scanned.clear();
		dirty.clear();
		lastCenter = null;
		manMade = null;
		version++;
	}

	public void chunkLoaded(ChunkPos pos) {
		queue.add(pos);
	}

	public void chunkUnloaded(ChunkPos pos) {
		queue.remove(pos);
		scanned.remove(pos);
		if (!sf.sticky.get() && hits.remove(pos) != null) version++;
	}

	public void blockChanged(BlockPos pos) {
		ChunkPos chunk = ChunkPos.containing(pos);
		if (scanned.contains(chunk)) dirty.add(chunk);
	}

	public void blockBroken(BlockPos pos) {
		trails.onBreak(pos);
	}

	/** Scans queued chunks until the budget runs out; returns what is new. */
	public List<Found> tick(ClientLevel level, Player player) {
		List<Found> found = new ArrayList<>();
		ChunkPos here = player.chunkPosition();
		int radius = sf.scanRadius.intValue();
		if (!here.equals(lastCenter)) {
			lastCenter = here;
			follow(level, here, radius);
		}
		// Changed chunks wait a little, so a busy redstone build isn't rescanned every tick.
		if (++ticks % 40 == 0 && !dirty.isEmpty()) {
			queue.addAll(dirty);
			dirty.clear();
		}
		if (anyBlockDetector()) {
			long deadline = System.nanoTime() + (long) (sf.timeBudget.get() * 1_000_000);
			while (!queue.isEmpty() && System.nanoTime() < deadline) {
				ChunkPos pos = nearest(here);
				queue.remove(pos);
				if (Math.abs(pos.x() - here.x()) > radius || Math.abs(pos.z() - here.z()) > radius) continue;
				LevelChunk chunk = level.getChunkSource().getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false);
				if (chunk == null) continue;
				scanned.add(pos);
				Map<Kind, List<AABB>> result = scan(level, chunk);
				Map<Kind, List<AABB>> before = result.isEmpty() ? hits.remove(pos) : hits.put(pos, result);
				for (Map.Entry<Kind, List<AABB>> e : result.entrySet()) {
					if (before == null || !before.containsKey(e.getKey())) found.add(new Found(e.getKey(), e.getValue().getFirst()));
				}
				if (!result.equals(before == null ? Map.of() : before)) version++;
			}
		} else {
			queue.clear();
		}
		if (sf.miningTrails.get() && ticks % 20 == 0) {
			for (MiningTrails.Trail t : trails.update(level, player, sf.trailScanDistance.get(), sf.trailMaxY.intValue(),
				sf.trailJoin.get(), sf.trailMinDrops.intValue(), sf.trailLifetime.intValue())) {
				var p = t.points().getFirst();
				found.add(new Found(Kind.TRAIL, new AABB(p.x - 0.25, p.y, p.z - 0.25, p.x + 0.25, p.y + 0.5, p.z + 0.25)));
			}
		}
		return found;
	}

	private boolean anyBlockDetector() {
		return sf.manMadeBlocks.get() || sf.tunnels.get() || sf.pockets.get() || sf.skyBuilds.get() || sf.netherRoof.get() || sf.signsOfTime.get();
	}

	/** Queue the area's chunks not yet scanned; outside a non-sticky area, finds are dropped. */
	private void follow(ClientLevel level, ChunkPos here, int radius) {
		for (int x = here.x() - radius; x <= here.x() + radius; x++) {
			for (int z = here.z() - radius; z <= here.z() + radius; z++) {
				ChunkPos pos = new ChunkPos(x, z);
				if (!scanned.contains(pos) && level.hasChunk(x, z)) queue.add(pos);
			}
		}
		if (sf.sticky.get()) return;
		boolean removed = hits.keySet().removeIf(pos -> Math.abs(pos.x() - here.x()) > radius || Math.abs(pos.z() - here.z()) > radius);
		scanned.removeIf(pos -> Math.abs(pos.x() - here.x()) > radius || Math.abs(pos.z() - here.z()) > radius);
		if (removed) version++;
	}

	private ChunkPos nearest(ChunkPos here) {
		ChunkPos best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (ChunkPos pos : queue) {
			int d = Math.max(Math.abs(pos.x() - here.x()), Math.abs(pos.z() - here.z()));
			if (d < bestDistance) {
				bestDistance = d;
				best = pos;
			}
		}
		return best;
	}

	/** Every enabled block detector over one chunk. */
	public Map<Kind, List<AABB>> scan(Level level, LevelChunk chunk) {
		Map<Kind, List<AABB>> out = new EnumMap<>(Kind.class);
		int cap = sf.maxPerChunk.intValue();
		int maxY = level.getMaxY() - 1;
		boolean nether = level.dimension() == Level.NETHER, end = level.dimension() == Level.END;
		if (sf.manMadeBlocks.get()) {
			if (manMade == null) manMade = BlockRules.manMade(blocks(sf.naturalBlocks.get()), blocks(NaturalBlocks.MAN_MADE), blocks(NaturalBlocks.MAN_MADE_WHEN_DRY));
			collect(out, Kind.MAN_MADE, list -> BlockRules.scanBlocks(chunk, sf.manMadeMinY.intValue(), sf.manMadeMaxY.intValue(), cap, manMade, list));
		}
		if (sf.tunnels.get()) {
			ShapeScanner.scan(level, chunk, new ShapeScanner.Config(sf.holes.get(), sf.straightTunnels.get(), sf.stairs.get(),
				sf.columns.get(), sf.minHoleDepth.intValue(), sf.minTunnelLength.intValue(), sf.maxTunnelWidth.intValue(),
				sf.maxTunnelHeight.intValue(), sf.minStairLength.intValue(), sf.minColumnHeight.intValue(), sf.naturalWalls.get(),
				sf.naturalWallRatio.get(), cap), out);
		}
		if (sf.signsOfTime.get()) collect(out, Kind.AGE, list -> BlockRules.signsOfTime(chunk, sf.ageScore.get(), cap, list));
		if (sf.pockets.get()) collect(out, Kind.POCKET, list -> BlockRules.pockets(level, chunk, sf.pocketsAirOnly.get(), cap, list));
		if (sf.skyBuilds.get() && !nether) collect(out, Kind.SKY, list -> BlockRules.scanBlocks(chunk, sf.skyY.intValue() + 1, maxY, cap, BlockRules.skyBuild(end), list));
		if (sf.netherRoof.get() && nether) {
			collect(out, Kind.ROOF, list -> {
				BlockRules.scanBlocks(chunk, BlockRules.ROOF_Y, maxY, cap, BlockRules.roof(sf.roofIgnoreMushrooms.get()), list);
				if (sf.roofLadders.get()) BlockRules.scanBlocks(chunk, BlockRules.ROOF_Y - 10, BlockRules.ROOF_Y - 1, cap, s -> s.is(Blocks.LADDER), list);
			});
		}
		return out;
	}

	private static void collect(Map<Kind, List<AABB>> out, Kind kind, java.util.function.Consumer<List<AABB>> scan) {
		List<AABB> list = new ArrayList<>();
		scan.accept(list);
		if (!list.isEmpty()) out.put(kind, list);
	}

	private static Set<Block> blocks(List<String> ids) {
		Set<Block> set = new HashSet<>();
		for (String id : ids) {
			Block block = BlockIds.of(id);
			if (block != null) set.add(block);
		}
		return set;
	}
}
