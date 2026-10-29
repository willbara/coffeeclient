package dev.coffeeclient.util;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Where an entity stands in block cells: the columns its box covers at the level it rests on, and
 * the ring of cells around them (a surround's blocks, a trap's walls, a surround's gaps). Blocks are
 * read through {@link Holes.Blocks} so it can be tested.
 */
public final class Footprint {
	/** How far below the box a level is still looked for (a short drop lands there). */
	public static final int MAX_DROP = 3;

	private Footprint() {
	}

	/** The world as {@link Holes} kinds. */
	public static Holes.Blocks blocks(BlockGetter level) {
		return (x, y, z) -> kind(level, new BlockPos(x, y, z));
	}

	/** Bedrock, blast-proof (explosion resistance of obsidian's class), other solid, or open. */
	public static int kind(BlockGetter level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.getCollisionShape(level, pos).isEmpty()) return Holes.OPEN;
		if (state.is(Blocks.BEDROCK)) return Holes.BEDROCK;
		return state.getBlock().getExplosionResistance() >= 600 ? Holes.BLAST_PROOF : Holes.SOLID;
	}

	/** The block columns the box stands in, at the level it rests on (or will within a short drop). */
	public static LongSet columns(AABB box, Holes.Blocks blocks) {
		int y = level(box, blocks);
		LongSet columns = new LongOpenHashSet();
		for (int x = Mth.floor(box.minX + 1.0E-4); x <= Mth.floor(box.maxX - 1.0E-4); x++) {
			for (int z = Mth.floor(box.minZ + 1.0E-4); z <= Mth.floor(box.maxZ - 1.0E-4); z++) {
				columns.add(BlockPos.asLong(x, y, z));
			}
		}
		return columns;
	}

	/** The y of the cells the box stands in: the first level down with something solid under one of its columns. */
	public static int level(AABB box, Holes.Blocks blocks) {
		int top = Mth.floor(box.minY + 0.4);
		for (int level = top; level > Mth.floor(box.minY) - MAX_DROP; level--) {
			for (int x = Mth.floor(box.minX + 1.0E-4); x <= Mth.floor(box.maxX - 1.0E-4); x++) {
				for (int z = Mth.floor(box.minZ + 1.0E-4); z <= Mth.floor(box.maxZ - 1.0E-4); z++) {
					if (blocks.at(x, level - 1, z) != Holes.OPEN) return level;
				}
			}
		}
		return top;
	}

	/** True when every cell around the columns is solid, or with {@code blastProof} bedrock or blast-proof: standing in a hole. */
	public static boolean walled(LongSet columns, Holes.Blocks blocks, boolean blastProof) {
		for (BlockPos wall : ring(columns)) {
			int kind = blocks.at(wall.getX(), wall.getY(), wall.getZ());
			if (kind == Holes.OPEN || blastProof && kind == Holes.SOLID) return false;
		}
		return true;
	}

	/** The cells horizontally around the columns. */
	public static List<BlockPos> ring(LongSet columns) {
		List<BlockPos> ring = new ArrayList<>();
		LongSet seen = new LongOpenHashSet();
		for (long key : columns) {
			BlockPos column = BlockPos.of(key);
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos side = column.relative(direction);
				if (!columns.contains(side.asLong()) && seen.add(side.asLong())) ring.add(side);
			}
		}
		return ring;
	}
}
