package dev.coffeeclient.module.modules.world.stashfinder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Per-chunk block rules for StashFinder: man-made blocks (BaseFinder), builds above a height
 * (SkyBuildESP), the Nether roof (RoofESP) and pockets dug into bedrock (BedrockStash).
 */
final class BlockRules {
	private BlockRules() {
	}

	/** Nether roof: anything at or above this Y was put there by a player. */
	static final int ROOF_Y = 128;
	/** A bedrock pocket bigger than this is open terrain, not a stash. */
	private static final int MAX_POCKET = 4096;
	private static final Set<Block> SKY_NATURAL = Set.of(Blocks.STONE, Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT,
		Blocks.ROOTED_DIRT, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.GRAVEL, Blocks.CLAY, Blocks.SAND, Blocks.RED_SAND,
		Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.TUFF, Blocks.CALCITE,
		Blocks.SMOOTH_BASALT, Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE, Blocks.SNOW, Blocks.SNOW_BLOCK, Blocks.POWDER_SNOW,
		Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.WATER, Blocks.LAVA, Blocks.DRIPSTONE_BLOCK,
		Blocks.POINTED_DRIPSTONE, Blocks.MOSS_BLOCK, Blocks.MOSS_CARPET);

	/** Blocks in sections between minY and maxY that pass {@code manMade}, at most {@code cap}. */
	static void scanBlocks(LevelChunk chunk, int minY, int maxY, int cap, Predicate<BlockState> manMade, List<AABB> out) {
		LevelChunkSection[] sections = chunk.getSections();
		int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
		for (int i = 0; i < sections.length && out.size() < cap; i++) {
			LevelChunkSection section = sections[i];
			int sectionY = chunk.getSectionYFromSectionIndex(i) << 4;
			if (section == null || section.hasOnlyAir() || sectionY + 15 < minY || sectionY > maxY) continue;
			// The palette says at once whether the section can hold a match at all.
			if (!section.getStates().maybeHas(manMade)) continue;
			for (int ly = 0; ly < 16 && out.size() < cap; ly++) {
				int y = sectionY + ly;
				if (y < minY || y > maxY) continue;
				for (int lx = 0; lx < 16; lx++) {
					for (int lz = 0; lz < 16; lz++) {
						if (manMade.test(section.getBlockState(lx, ly, lz)) && out.size() < cap)
							out.add(new AABB(baseX + lx, y, baseZ + lz, baseX + lx + 1, y + 1, baseZ + lz + 1));
					}
				}
			}
		}
	}

	/**
	 * How much a block says "someone has been here a long time": rotated deepslate (only placed),
	 * hoppers and redstone parts, and growths that only finish while a chunk stays loaded (full kelp,
	 * cocoa and bamboo, amethyst clusters, bee nests full of honey). 0 for everything else.
	 */
	static double ageWeight(BlockState s) {
		if (s.is(Blocks.DEEPSLATE)) return s.getValue(BlockStateProperties.AXIS) != Direction.Axis.Y ? 3 : 0;
		if (s.is(Blocks.HOPPER)) return 2;
		if (s.is(Blocks.COMPARATOR) || s.is(Blocks.REPEATER) || s.is(Blocks.OBSERVER) || s.is(Blocks.REDSTONE_WIRE)
			|| s.is(Blocks.PISTON) || s.is(Blocks.STICKY_PISTON) || s.is(Blocks.DROPPER) || s.is(Blocks.DISPENSER)) return 1;
		if (s.is(Blocks.KELP)) return s.getValue(BlockStateProperties.AGE_25) == 25 ? 0.5 : 0;
		if (s.is(Blocks.COCOA)) return s.getValue(BlockStateProperties.AGE_2) == 2 ? 0.5 : 0;
		if (s.is(Blocks.BAMBOO)) return s.getValue(BlockStateProperties.STAGE) == 1 ? 0.2 : 0;
		if (s.is(Blocks.AMETHYST_CLUSTER)) return 0.2;
		if (s.is(Blocks.BEE_NEST) || s.is(Blocks.BEEHIVE)) return s.getValue(BlockStateProperties.LEVEL_HONEY) == 5 ? 1 : 0;
		return 0;
	}

	/** The chunk's weighted blocks, kept only when their weights add up to {@code threshold}. */
	static void signsOfTime(LevelChunk chunk, double threshold, int cap, List<AABB> out) {
		List<AABB> found = new ArrayList<>();
		// The predicate also screens palettes, so the weights are added up afterwards.
		scanBlocks(chunk, chunk.getMinY(), chunk.getMaxY(), cap, state -> ageWeight(state) > 0, found);
		double score = 0;
		for (AABB box : found) score += ageWeight(chunk.getBlockState(BlockPos.containing(box.minX, box.minY, box.minZ)));
		if (score >= threshold) out.addAll(found);
	}

	/** Man-made unless natural; a forced list wins, and ocean blocks only count out of water. */
	static Predicate<BlockState> manMade(Set<Block> natural, Set<Block> always, Set<Block> whenDry) {
		return state -> {
			Block block = state.getBlock();
			if (always.contains(block)) return true;
			if (whenDry.contains(block)) return state.getFluidState().isEmpty();
			return !state.isAir() && !natural.contains(block);
		};
	}

	/** Sky builds: in the End anything but end-city blocks, elsewhere anything that isn't terrain or plants. */
	static Predicate<BlockState> skyBuild(boolean end) {
		return state -> {
			if (state.isAir()) return false;
			String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
			if (end) return !(path.startsWith("purpur_") || path.startsWith("end_stone_brick") || path.equals("end_rod")
				|| path.contains("shulker_box") || path.equals("magenta_stained_glass") || path.equals("ender_chest")
				|| path.equals("chest") || path.equals("dragon_head") || path.equals("dragon_wall_head") || path.equals("ladder"));
			if (SKY_NATURAL.contains(state.getBlock())) return false;
			return !(path.endsWith("_ore") || path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_leaves")
				|| path.endsWith("_sapling") || path.endsWith("_stem") || path.endsWith("_hyphae") || path.contains("mushroom")
				|| path.contains("azalea") || path.contains("lichen") || path.contains("vines") || path.contains("roots")
				|| path.equals("short_grass") || path.equals("tall_grass") || path.equals("fern") || path.equals("large_fern")
				|| path.equals("dead_bush") || path.equals("bamboo") || path.equals("cactus") || path.equals("sugar_cane")
				|| path.equals("vine"));
		};
	}

	/** Nether roof blocks; mushrooms grow up there on their own, so they can be skipped. */
	static Predicate<BlockState> roof(boolean ignoreMushrooms) {
		return state -> !state.isAir() && !(ignoreMushrooms && (state.is(Blocks.RED_MUSHROOM) || state.is(Blocks.BROWN_MUSHROOM)));
	}

	/** Bedrock layers where pockets are looked for: the floor, plus the Nether roof. */
	static List<int[]> bedrockBands(Level level) {
		List<int[]> bands = new ArrayList<>();
		if (level.dimension() == Level.END) return bands;
		bands.add(new int[]{level.getMinY(), level.getMinY() + 5});
		if (level.dimension() == Level.NETHER) bands.add(new int[]{122, 127});
		return bands;
	}

	/**
	 * Regions of non-bedrock blocks fully closed in by bedrock, with at least one two-tall column (so a
	 * player fits). Each column of a pocket becomes one box.
	 */
	static void pockets(Level level, LevelChunk chunk, boolean airOnly, int cap, List<AABB> out) {
		Set<Long> visited = new HashSet<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
		for (int[] band : bedrockBands(level)) {
			for (int x = baseX; x < baseX + 16; x++) {
				for (int z = baseZ; z < baseZ + 16; z++) {
					for (int y = band[0]; y <= band[1] && out.size() < cap; y++) {
						pos.set(x, y, z);
						if (visited.contains(pos.asLong()) || level.getBlockState(pos).is(Blocks.BEDROCK) || !touchesBedrock(level, pos)) continue;
						pocket(level, pos.immutable(), visited, airOnly, out);
					}
				}
			}
		}
	}

	private static boolean touchesBedrock(Level level, BlockPos pos) {
		for (Direction dir : Direction.values()) if (level.getBlockState(pos.relative(dir)).is(Blocks.BEDROCK)) return true;
		return false;
	}

	private static void pocket(Level level, BlockPos start, Set<Long> visited, boolean airOnly, List<AABB> out) {
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		Set<Long> seen = new HashSet<>();
		List<BlockPos> cells = new ArrayList<>();
		queue.add(start);
		seen.add(start.asLong());
		boolean allAir = true, open = false;
		while (!queue.isEmpty()) {
			BlockPos current = queue.removeFirst();
			BlockState state = level.getBlockState(current);
			cells.add(current);
			if (cells.size() > MAX_POCKET) {
				open = true;
				break;
			}
			if (!state.isAir()) allAir = false;
			for (Direction dir : Direction.values()) {
				BlockPos next = current.relative(dir);
				// Leaving the world or the loaded area means it isn't closed in.
				if (!level.hasChunkAt(next)) {
					open = true;
					continue;
				}
				if (level.getBlockState(next).is(Blocks.BEDROCK) || !seen.add(next.asLong())) continue;
				queue.add(next);
			}
			if (open) break;
		}
		visited.addAll(seen);
		if (open || cells.size() < 2 || airOnly && !allAir) return;
		List<AABB> boxes = new ArrayList<>();
		boolean tall = false;
		for (BlockPos cell : cells) {
			if (seen.contains(cell.above().asLong())) tall = true;
			if (seen.contains(cell.below().asLong())) continue;
			// The bottom cell of each column: walk up to make one box.
			int top = cell.getY();
			while (seen.contains(BlockPos.asLong(cell.getX(), top + 1, cell.getZ()))) top++;
			boxes.add(new AABB(cell.getX(), cell.getY(), cell.getZ(), cell.getX() + 1, top + 1, cell.getZ() + 1));
		}
		if (tall) out.addAll(boxes);
	}
}
