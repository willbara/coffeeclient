package dev.coffeeclient.module.modules.world.stashfinder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds dug shapes in one chunk: 1x1/2x2 holes, straight tunnels, staircases, and ladder, bubble and
 * water columns in a shaft. It scans a whole chunk per
 * call instead of resuming, and filters out natural walls.
 */
final class ShapeScanner {
	record Config(boolean holes, boolean tunnels, boolean stairs, boolean columns, int minHoleDepth, int minTunnelLength,
				  int maxTunnelWidth, int maxTunnelHeight, int minStairLength, int minColumnHeight, boolean naturalWalls,
				  double naturalRatio, int maxPerChunk) {
	}

	private static final int MIN_HOLE_WIDTH = 1, MAX_HOLE_WIDTH = 2;
	private static final int MIN_TUNNEL_WIDTH = 1, MIN_TUNNEL_HEIGHT = 2;
	private static final int MIN_STAIR_HEIGHT = 2, MAX_STAIR_HEIGHT = 4;
	private static final Direction[] CARDINALS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
	/** Tunnels are walked east and south only; the other two would find the same run backwards. */
	private static final Direction[] TUNNEL_DIRECTIONS = {Direction.EAST, Direction.SOUTH};
	private static final Map<Block, Boolean> NATURAL_WALL = new ConcurrentHashMap<>();

	private final Level level;
	private final Config c;
	private final Set<Long> tunnelCells = new HashSet<>();

	private ShapeScanner(Level level, Config config) {
		this.level = level;
		this.c = config;
	}

	static void scan(Level level, LevelChunk chunk, Config config, Map<Kind, List<AABB>> out) {
		new ShapeScanner(level, config).scan(chunk, out);
	}

	private void scan(LevelChunk chunk, Map<Kind, List<AABB>> out) {
		List<AABB> holes = new ArrayList<>(), tunnels = new ArrayList<>(), stairs = new ArrayList<>(), columns = new ArrayList<>();
		int minY = level.getMinY(), maxY = level.getMaxY() - 1;
		LevelChunkSection[] sections = chunk.getSections();
		int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
		BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
		for (int i = sections.length - 1; i >= 0; i--) {
			LevelChunkSection section = sections[i];
			// Fully solid sections have nothing to walk through.
			if (section == null || section.hasOnlyAir() || !section.getStates().maybeHas(ShapeScanner::maybeOpen)) continue;
			int sectionY = chunk.getSectionYFromSectionIndex(i) << 4;
			for (int ly = 15; ly >= 0; ly--) {
				int y = sectionY + ly;
				if (y < minY || y > maxY) continue;
				for (int lx = 0; lx < 16; lx++) {
					for (int lz = 0; lz < 16; lz++) {
						if (full(holes) && full(tunnels) && full(stairs) && full(columns)) {
							put(out, holes, tunnels, stairs, columns);
							return;
						}
						BlockState state = section.getBlockState(lx, ly, lz);
						mutable.set(baseX + lx, y, baseZ + lz);
						BlockPos pos = mutable.immutable();
						if (c.columns() && !full(columns)) {
							ladderColumn(pos, state, minY, maxY, columns);
							waterColumn(pos, state, minY, maxY, columns);
						}
						boolean passable = passable(pos, state);
						boolean holePassable = c.holes() && (passable || state.getBlock() instanceof LadderBlock);
						boolean tunnelPassable = c.tunnels() && tunnelPassable(pos, state);
						if (!passable && !holePassable && !tunnelPassable) continue;
						boolean solidBelow = (tunnelPassable || passable && c.stairs()) && solid(pos.below());
						if (holePassable && !full(holes)) hole(pos, maxY, holes);
						if (tunnelPassable && solidBelow && !full(tunnels)) for (Direction dir : TUNNEL_DIRECTIONS) tunnel(pos, dir, tunnels);
						if (c.stairs() && passable && solidBelow && !full(stairs)) for (Direction dir : CARDINALS) staircase(pos, dir, stairs);
					}
				}
			}
		}
		put(out, holes, tunnels, stairs, columns);
	}

	private boolean full(List<AABB> list) {
		return list.size() >= c.maxPerChunk();
	}

	private static void put(Map<Kind, List<AABB>> out, List<AABB> holes, List<AABB> tunnels, List<AABB> stairs, List<AABB> columns) {
		if (!holes.isEmpty()) out.put(Kind.HOLE, holes);
		if (!tunnels.isEmpty()) out.put(Kind.TUNNEL, tunnels);
		if (!stairs.isEmpty()) out.put(Kind.STAIR, stairs);
		if (!columns.isEmpty()) out.put(Kind.COLUMN, columns);
	}

	private static boolean maybeOpen(BlockState state) {
		return state.isAir() || !state.getFluidState().isEmpty() || !state.canOcclude() || state.getBlock() instanceof LadderBlock;
	}

	// ---------------------------------------------------------------------------------------------
	// Shapes
	// ---------------------------------------------------------------------------------------------

	private void hole(BlockPos start, int maxY, List<AABB> out) {
		int width = -1;
		for (int w = MAX_HOLE_WIDTH; w >= MIN_HOLE_WIDTH; w--) {
			if (holeSection(start, w)) {
				width = w;
				break;
			}
		}
		// Only the north-west corner of the bottom layer starts a hole.
		if (width < 1 || holeSection(start.west(), width) || holeSection(start.north(), width) || holeSection(start.below(), width)) return;
		BlockPos.MutableBlockPos cursor = start.mutable();
		int depth = 0, walls = 0, natural = 0;
		while (cursor.getY() <= maxY && holeSection(cursor, width)) {
			if (c.naturalWalls()) {
				for (int i = 0; i < width; i++) {
					for (BlockPos wall : new BlockPos[]{cursor.offset(-1, 0, i), cursor.offset(width, 0, i), cursor.offset(i, 0, -1), cursor.offset(i, 0, width)}) {
						walls++;
						if (naturalWall(wall)) natural++;
					}
				}
			}
			cursor.move(Direction.UP);
			depth++;
		}
		if (depth < c.minHoleDepth() || c.naturalWalls() && walls > 0 && natural / (double) walls < c.naturalRatio()) return;
		add(out, new AABB(start.getX(), start.getY(), start.getZ(), start.getX() + width, cursor.getY(), start.getZ() + width));
	}

	private boolean holeSection(BlockPos pos, int width) {
		for (int x = 0; x < width; x++) {
			for (int z = 0; z < width; z++) {
				BlockPos p = pos.offset(x, 0, z);
				BlockState state = loaded(p);
				if (state == null || !passable(p, state) && !(state.getBlock() instanceof LadderBlock)) return false;
			}
		}
		for (int i = 0; i < width; i++) {
			if (!solid(pos.offset(-1, 0, i)) || !solid(pos.offset(width, 0, i)) || !solid(pos.offset(i, 0, -1)) || !solid(pos.offset(i, 0, width)))
				return false;
		}
		return true;
	}

	private void tunnel(BlockPos start, Direction dir, List<AABB> out) {
		long visit = start.asLong() * 2 + (dir.getAxis() == Direction.Axis.X ? 0 : 1);
		if (tunnelCells.contains(visit) || !tunnelSection(start, dir)) return;
		List<Long> visits = new ArrayList<>();
		BlockPos.MutableBlockPos cursor = start.mutable();
		BlockPos end = start;
		int length = 0, minHeight = Integer.MAX_VALUE, maxHeight = Integer.MIN_VALUE, minWidth = Integer.MAX_VALUE, maxWidth = Integer.MIN_VALUE;
		int maxLength = Math.max(16, c.minTunnelLength());
		while (length < maxLength && tunnelSection(cursor, dir)) {
			visits.add(cursor.asLong() * 2 + (dir.getAxis() == Direction.Axis.X ? 0 : 1));
			int height = tunnelHeight(cursor);
			int width = tunnelWidth(cursor, dir, height);
			minHeight = Math.min(minHeight, height);
			maxHeight = Math.max(maxHeight, height);
			minWidth = Math.min(minWidth, width);
			maxWidth = Math.max(maxWidth, width);
			end = cursor.immutable();
			cursor.move(dir);
			length++;
		}
		if (length < c.minTunnelLength() || maxHeight - minHeight > 1 || maxWidth - minWidth > 1
			|| minWidth < MIN_TUNNEL_WIDTH || maxWidth > c.maxTunnelWidth()) return;
		tunnelCells.addAll(visits);
		BlockPos sideEnd = end.relative(dir.getClockWise(), maxWidth - 1);
		add(out, new AABB(Math.min(Math.min(start.getX(), end.getX()), sideEnd.getX()), start.getY(),
			Math.min(Math.min(start.getZ(), end.getZ()), sideEnd.getZ()),
			Math.max(Math.max(start.getX(), end.getX()), sideEnd.getX()) + 1, start.getY() + maxHeight,
			Math.max(Math.max(start.getZ(), end.getZ()), sideEnd.getZ()) + 1));
	}

	private boolean tunnelSection(BlockPos pos, Direction dir) {
		int height = tunnelHeight(pos);
		if (height < MIN_TUNNEL_HEIGHT || height > c.maxTunnelHeight()) return false;
		int width = tunnelWidth(pos, dir, height);
		if (width < MIN_TUNNEL_WIDTH || width > c.maxTunnelWidth()) return false;
		Direction left = dir.getCounterClockWise(), right = dir.getClockWise();
		BlockPos rightEdge = pos.relative(right, width - 1);
		int natural = 0, walls = 0;
		for (int w = 0; w < width; w++) {
			BlockPos lane = pos.relative(right, w);
			BlockPos floor = lane.below(), ceiling = lane.above(height);
			if (!solid(floor) || !solid(ceiling)) return false;
			if (c.naturalWalls()) {
				walls += 2;
				if (naturalWall(floor)) natural++;
				if (naturalWall(ceiling)) natural++;
			}
		}
		for (int i = 0; i < height; i++) {
			BlockPos l = pos.above(i).relative(left), r = rightEdge.above(i).relative(right);
			if (!solid(l) || !solid(r)) return false;
			if (c.naturalWalls()) {
				walls += 2;
				if (naturalWall(l)) natural++;
				if (naturalWall(r)) natural++;
			}
		}
		return !c.naturalWalls() || natural / (double) Math.max(1, walls) >= c.naturalRatio();
	}

	/** Lanes side by side to the right; one past the maximum means "too wide". */
	private int tunnelWidth(BlockPos pos, Direction dir, int height) {
		Direction side = dir.getClockWise();
		int width = 0;
		while (width < c.maxTunnelWidth() && tunnelLane(pos.relative(side, width), height)) width++;
		if (width == c.maxTunnelWidth() && tunnelLane(pos.relative(side, width), height)) return width + 1;
		return width;
	}

	private boolean tunnelLane(BlockPos pos, int height) {
		if (!solid(pos.below()) || !solid(pos.above(height))) return false;
		for (int i = 0; i < height; i++) if (!tunnelPassable(pos.above(i))) return false;
		return true;
	}

	private int tunnelHeight(BlockPos pos) {
		int max = c.maxTunnelHeight();
		int height = 0;
		while (height < max && tunnelPassable(pos.above(height))) height++;
		if (height == max && tunnelPassable(pos.above(height))) return max + 1;
		return height;
	}

	private void staircase(BlockPos start, Direction dir, List<AABB> out) {
		if (!stairSection(start, dir) || stairSection(start.relative(dir.getOpposite()).below(), dir)) return;
		BlockPos.MutableBlockPos cursor = start.mutable();
		List<AABB> steps = new ArrayList<>();
		int minHeight = Integer.MAX_VALUE, maxHeight = Integer.MIN_VALUE;
		while (stairSection(cursor, dir) && steps.size() < 64) {
			int height = clearHeight(cursor, MAX_STAIR_HEIGHT);
			minHeight = Math.min(minHeight, height);
			maxHeight = Math.max(maxHeight, height);
			steps.add(new AABB(cursor.getX(), cursor.getY(), cursor.getZ(), cursor.getX() + 1, cursor.getY() + height, cursor.getZ() + 1));
			cursor.move(dir);
			cursor.move(Direction.UP);
		}
		if (steps.size() < c.minStairLength() || maxHeight - minHeight > 1) return;
		for (AABB step : steps) add(out, step);
	}

	private boolean stairSection(BlockPos pos, Direction dir) {
		int height = clearHeight(pos, MAX_STAIR_HEIGHT);
		if (height < MIN_STAIR_HEIGHT || height > MAX_STAIR_HEIGHT) return false;
		if (!solid(pos.below()) || !solid(pos.above(height))) return false;
		Direction left = dir.getCounterClockWise(), right = dir.getClockWise();
		int natural = 0, walls = 2;
		if (c.naturalWalls()) {
			if (naturalWall(pos.below())) natural++;
			if (naturalWall(pos.above(height))) natural++;
		}
		for (int i = 0; i < height; i++) {
			BlockPos l = pos.above(i).relative(left), r = pos.above(i).relative(right);
			if (!solid(l) || !solid(r)) return false;
			if (c.naturalWalls()) {
				walls += 2;
				if (naturalWall(l)) natural++;
				if (naturalWall(r)) natural++;
			}
		}
		return !c.naturalWalls() || natural / (double) walls >= c.naturalRatio();
	}

	private int clearHeight(BlockPos pos, int max) {
		int height = 0;
		while (height < max && passable(pos.above(height))) height++;
		return height;
	}

	private void ladderColumn(BlockPos start, BlockState state, int minY, int maxY, List<AABB> out) {
		if (!(state.getBlock() instanceof LadderBlock)) return;
		BlockState below = loaded(start.below());
		if (start.getY() > minY && below != null && below.getBlock() instanceof LadderBlock) return;
		BlockPos.MutableBlockPos cursor = start.mutable();
		int height = 0;
		while (cursor.getY() <= maxY) {
			BlockState s = loaded(cursor);
			if (s == null || !(s.getBlock() instanceof LadderBlock)) break;
			height++;
			cursor.move(Direction.UP);
		}
		if (height >= c.minColumnHeight()) add(out, new AABB(start.getX(), start.getY(), start.getZ(), start.getX() + 1, start.getY() + height, start.getZ() + 1));
	}

	/** Bubble columns and plain water columns, both only inside a 1x1 shaft. */
	private void waterColumn(BlockPos start, BlockState state, int minY, int maxY, List<AABB> out) {
		if (!state.getFluidState().is(FluidTags.WATER)) return;
		boolean bubble = state.getBlock() instanceof BubbleColumnBlock;
		if (start.getY() > minY) {
			BlockState below = loaded(start.below());
			if (below != null && sameColumn(below, bubble) && inShaft(start.below())) return;
		}
		BlockPos.MutableBlockPos cursor = start.mutable();
		int height = 0;
		while (cursor.getY() <= maxY) {
			BlockState s = loaded(cursor);
			if (s == null || !sameColumn(s, bubble) || !inShaft(cursor)) break;
			height++;
			cursor.move(Direction.UP);
		}
		if (height >= c.minColumnHeight()) add(out, new AABB(start.getX(), start.getY(), start.getZ(), start.getX() + 1, start.getY() + height, start.getZ() + 1));
	}

	private static boolean sameColumn(BlockState state, boolean bubble) {
		return state.getFluidState().is(FluidTags.WATER) && state.getBlock() instanceof BubbleColumnBlock == bubble;
	}

	private boolean inShaft(BlockPos pos) {
		for (Direction dir : CARDINALS) if (!solid(pos.relative(dir))) return false;
		return true;
	}

	// ---------------------------------------------------------------------------------------------
	// Block tests
	// ---------------------------------------------------------------------------------------------

	private static void add(List<AABB> out, AABB box) {
		for (AABB other : out) if (other.intersects(box)) return;
		out.add(box);
	}

	/** The state, or null when its chunk isn't loaded (an edge of what we know). */
	private BlockState loaded(BlockPos pos) {
		if (pos.getY() < level.getMinY() || pos.getY() >= level.getMaxY() || !level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return null;
		return level.getBlockState(pos);
	}

	private boolean passable(BlockPos pos) {
		BlockState state = loaded(pos);
		return state != null && passable(pos, state);
	}

	private boolean passable(BlockPos pos, BlockState state) {
		return state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty();
	}

	private boolean tunnelPassable(BlockPos pos) {
		BlockState state = loaded(pos);
		return state != null && tunnelPassable(pos, state);
	}

	private boolean tunnelPassable(BlockPos pos, BlockState state) {
		return passable(pos, state) || state.getBlock() instanceof TorchBlock || state.getBlock() instanceof WallTorchBlock;
	}

	private boolean solid(BlockPos pos) {
		BlockState state = loaded(pos);
		return state != null && state.getFluidState().isEmpty() && state.isCollisionShapeFullBlock(level, pos);
	}

	private boolean naturalWall(BlockPos pos) {
		BlockState state = loaded(pos);
		if (state == null || !state.getFluidState().isEmpty() || !state.isCollisionShapeFullBlock(level, pos)) return false;
		return NATURAL_WALL.computeIfAbsent(state.getBlock(), b -> looksNatural(BuiltInRegistries.BLOCK.getKey(b).getPath().toLowerCase(Locale.ROOT)));
	}

	private static final String[] NOT_TERRAIN = {"planks", "log", "wood", "stripped", "fence", "stairs", "slab", "door", "trapdoor",
		"rail", "brick", "tile", "prismarine", "purpur", "quartz", "wool", "glass", "copper", "trial", "obsidian", "amethyst",
		"bookshelf", "chest", "torch", "lantern", "chain", "spawner", "barrel", "furnace", "sculk"};
	private static final String[] TERRAIN = {"stone", "deepslate", "netherrack", "blackstone", "basalt", "end_stone", "dirt",
		"gravel", "sand", "clay", "tuff", "calcite", "mud", "terracotta", "bedrock", "andesite", "diorite", "granite",
		"soul_sand", "soul_soil", "nylium"};

	static boolean looksNatural(String path) {
		for (String reject : NOT_TERRAIN) if (path.contains(reject)) return false;
		if (path.contains("ore") || path.contains("debris")) return true;
		for (String hint : TERRAIN) if (path.contains(hint)) return true;
		return false;
	}
}
