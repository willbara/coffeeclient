package dev.coffeeclient.module.modules.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Guesses whether a container came with world generation (a spawner room, a trial chamber or a
 * village) so StorageESP and StashFinder can hide it and leave the likely player-placed ones. Heuristics ported
 * from ChestESP. Only single chests are judged, plus trial-chamber barrels,
 * hoppers and dispensers.
 */
public final class NaturalLoot {
	/** Verdicts are kept this long (ticks), since the blocks around a container rarely change. */
	private static final int CACHE_TICKS = 100;

	private final Map<BlockPos, Boolean> cache = new HashMap<>();
	private ResourceKey<Level> dimension;
	private long cachedAt;
	/** Filled lazily once per scan, only when a check needs them. */
	private List<BlockPos> trialSpawners;
	private List<Vec3> villagers;

	public void clear() {
		cache.clear();
	}

	/** Call before each StorageESP scan. */
	public void beginScan(ClientLevel level) {
		long now = level.getGameTime();
		if (level.dimension() != dimension || now - cachedAt > CACHE_TICKS || now < cachedAt) {
			cache.clear();
			dimension = level.dimension();
			cachedAt = now;
		}
		trialSpawners = null;
		villagers = null;
	}

	public boolean hide(ClientLevel level, BlockEntity be, boolean spawners, boolean trials, boolean villages) {
		BlockPos pos = be.getBlockPos();
		Boolean cached = cache.get(pos);
		if (cached != null) return cached;
		boolean hide = judge(level, be, spawners, trials, villages);
		cache.put(pos.immutable(), hide);
		return hide;
	}

	private boolean judge(ClientLevel level, BlockEntity be, boolean spawners, boolean trials, boolean villages) {
		BlockPos pos = be.getBlockPos();
		if (be instanceof ChestBlockEntity) {
			BlockState state = be.getBlockState();
			if (!state.hasProperty(ChestBlock.TYPE) || state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) return false;
			if (spawners && level.dimension() == Level.OVERWORLD && nearSpawner(level, pos)) return true;
			if (trials && inTrialChamber(level, pos)) return true;
			return villages && villageChest(level, pos);
		}
		if (be instanceof BarrelBlockEntity || be instanceof HopperBlockEntity || be instanceof DispenserBlockEntity)
			return trials && inTrialChamber(level, pos);
		return false;
	}

	private static boolean nearSpawner(ClientLevel level, BlockPos center) {
		return countInBox(level, center, 7, 1, (pos, state) -> state.is(Blocks.SPAWNER)
			&& pos.distSqr(center) <= 49) > 0;
	}

	private boolean inTrialChamber(ClientLevel level, BlockPos pos) {
		if (pos.getY() < -64 || pos.getY() > 48) return false;
		// The container has to sit on (or against) the chamber's tuff and copper palette.
		boolean anchored = trialSupport(level.getBlockState(pos.below()));
		for (BlockPos side : new BlockPos[]{pos.north(), pos.south(), pos.east(), pos.west()}) {
			if (anchored) break;
			anchored = trialSupport(level.getBlockState(side));
		}
		if (!anchored) return false;
		for (BlockPos spawner : trialSpawners(level, pos)) if (spawner.distSqr(pos) <= 96 * 96) return true;
		return countInBox(level, pos, 4, 1, (p, state) -> trialBlock(state)) > 0;
	}

	/** Trial spawners and vaults in loaded chunks within 96 blocks of the first container asked about. */
	private List<BlockPos> trialSpawners(ClientLevel level, BlockPos near) {
		if (trialSpawners != null) return trialSpawners;
		List<BlockPos> found = new ArrayList<>();
		int cx = near.getX() >> 4, cz = near.getZ() >> 4;
		for (int x = cx - 7; x <= cx + 7; x++) {
			for (int z = cz - 7; z <= cz + 7; z++) {
				LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
				if (chunk == null) continue;
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					if (be instanceof TrialSpawnerBlockEntity || be instanceof VaultBlockEntity) found.add(be.getBlockPos());
				}
			}
		}
		return trialSpawners = found;
	}

	private boolean villageChest(ClientLevel level, BlockPos pos) {
		if (countInBox(level, pos, 4, 1, (p, state) -> state.getBlock() instanceof DoorBlock) == 0) return false;
		if (countInBox(level, pos, 6, 4, (p, state) -> state.is(Blocks.HAY_BLOCK)) >= 4) return true;
		if (countInBox(level, pos, 4, 1, (p, state) -> path(state.getBlock()).contains("glass_pane")) > 0) return true;
		Vec3 center = Vec3.atCenterOf(pos);
		for (Vec3 v : villagers(level)) if (v.distanceToSqr(center) <= 24 * 24) return true;
		return false;
	}

	private List<Vec3> villagers(ClientLevel level) {
		if (villagers != null) return villagers;
		List<Vec3> found = new ArrayList<>();
		for (Entity e : level.entitiesForRendering()) {
			if (e instanceof Villager || e instanceof IronGolem) found.add(e.position());
		}
		return villagers = found;
	}

	private static boolean trialSupport(BlockState state) {
		String path = path(state.getBlock());
		return path.contains("waxed") && path.contains("copper") || trialPalette(path);
	}

	private static boolean trialBlock(BlockState state) {
		String path = path(state.getBlock());
		return path.contains("trial_spawner") || path.contains("vault") || trialPalette(path);
	}

	private static boolean trialPalette(String path) {
		return path.contains("tuff_bricks") || path.contains("chiseled_tuff") || path.contains("polished_tuff")
			|| path.contains("tuff_stairs") || path.contains("tuff_slab") || path.contains("tuff_wall")
			|| path.contains("copper_bulb") || path.contains("waxed_copper");
	}

	private static String path(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).getPath();
	}

	private interface Match {
		boolean test(BlockPos pos, BlockState state);
	}

	/** Matching blocks in the cube of the given radius, counting up to {@code enough}. */
	private static int countInBox(ClientLevel level, BlockPos center, int range, int enough, Match match) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int count = 0;
		for (int x = -range; x <= range; x++) {
			for (int y = -range; y <= range; y++) {
				for (int z = -range; z <= range; z++) {
					pos.set(center.getX() + x, center.getY() + y, center.getZ() + z);
					if (match.test(pos, level.getBlockState(pos)) && ++count >= enough) return count;
				}
			}
		}
		return count;
	}
}
