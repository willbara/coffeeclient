package dev.coffeeclient.util;

/**
 * Finds 1x1, 2x1 and 2x2 holes, free of Minecraft types so it can be tested: a hole is open
 * cells ({@code depth} high) with a solid floor and solid walls on every side at feet level,
 * reported once from its north-west cell. Its safety is the weakest wall or floor block.
 */
public final class Holes {
	/** What a block is, for hole finding. */
	public static final int OPEN = 0, BEDROCK = 1, BLAST_PROOF = 2, SOLID = 3;

	public enum Type { SINGLE, DOUBLE_X, DOUBLE_Z, QUAD }

	public enum Safety { BEDROCK, MIXED, UNSAFE }

	/** A block lookup: one of {@link #OPEN}, {@link #BEDROCK}, {@link #BLAST_PROOF}, {@link #SOLID}. */
	public interface Blocks {
		int at(int x, int y, int z);
	}

	public record Hole(int x, int y, int z, Type type, Safety safety) {
		public int sizeX() {
			return type == Type.DOUBLE_X || type == Type.QUAD ? 2 : 1;
		}

		public int sizeZ() {
			return type == Type.DOUBLE_Z || type == Type.QUAD ? 2 : 1;
		}
	}

	private Holes() {
	}

	/** The hole whose north-west cell is (x, y, z), or null. */
	public static Hole find(Blocks b, int x, int y, int z, int depth) {
		if (!cell(b, x, y, z, depth) || b.at(x - 1, y, z) == OPEN || b.at(x, y, z - 1) == OPEN) return null;
		boolean east = cell(b, x + 1, y, z, depth), south = cell(b, x, y, z + 1, depth);
		Type type;
		if (!east && !south) type = Type.SINGLE;
		else if (east && south) type = cell(b, x + 1, y, z + 1, depth) ? Type.QUAD : null;
		else type = east ? Type.DOUBLE_X : Type.DOUBLE_Z;
		if (type == null) return null;
		int w = type == Type.DOUBLE_X || type == Type.QUAD ? 2 : 1, d = type == Type.DOUBLE_Z || type == Type.QUAD ? 2 : 1;
		int worst = BEDROCK;
		for (int i = 0; i < w; i++) {
			for (int k = 0; k < d; k++) {
				worst = Math.max(worst, b.at(x + i, y - 1, z + k));
				// The ring of walls around the footprint.
				if (i == 0) worst = Math.max(worst, wall(b, x - 1, y, z + k));
				if (i == w - 1) worst = Math.max(worst, wall(b, x + w, y, z + k));
				if (k == 0) worst = Math.max(worst, wall(b, x + i, y, z - 1));
				if (k == d - 1) worst = Math.max(worst, wall(b, x + i, y, z + d));
			}
		}
		if (worst > SOLID) return null;
		Safety safety = worst == BEDROCK ? Safety.BEDROCK : worst == BLAST_PROOF ? Safety.MIXED : Safety.UNSAFE;
		return new Hole(x, y, z, type, safety);
	}

	/** Open for {@code depth} blocks up, on a solid floor. */
	private static boolean cell(Blocks b, int x, int y, int z, int depth) {
		if (b.at(x, y - 1, z) == OPEN) return false;
		for (int i = 0; i < depth; i++) if (b.at(x, y + i, z) != OPEN) return false;
		return true;
	}

	/** A wall block's kind; an open one is no wall at all (beyond {@link #SOLID}). */
	private static int wall(Blocks b, int x, int y, int z) {
		int kind = b.at(x, y, z);
		return kind == OPEN ? SOLID + 1 : kind;
	}
}
