package dev.coffeeclient.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Where the stronghold an Eye of Ender points at probably is, from one or more throws, after
 * {@code StrongholdBayesianEstimator} (which follows Ninjabrain Bot's triangulation
 * paper). Many random stronghold layouts are generated the way the game places strongholds (rings
 * around 0,0, then snapped to a biome a few chunks away); each layout is weighted by how well the
 * direction from every throw to its nearest stronghold matches the measured direction (a normal error
 * of {@code sigma} degrees). The weights summed per chunk are the answer.
 *
 * <p>Plain Java on purpose, so it runs off the client thread and in unit tests.
 */
public final class StrongholdEstimator {
	public static final int STRONGHOLDS = 128;
	private static final int DISTANCE = 32;
	private static final int SPREAD = 3;
	/** The game moves each stronghold to a fitting biome up to this many chunks away. */
	private static final int SNAP = 7;
	private static final long SEED = 0x51F15EEDL;

	/** One throw: where it was thrown from (block coordinates) and the yaw the eye flew at (Minecraft degrees). */
	public record Throw(double x, double z, double yaw) {
	}

	public record Candidate(int chunkX, int chunkZ, double probability) {
		public int blockX() {
			return chunkX * 16 + 8;
		}

		public int blockZ() {
			return chunkZ * 16 + 8;
		}
	}

	/** The most likely chunks, best first. */
	public record Result(List<Candidate> candidates, int samples) {
		public Candidate best() {
			return candidates.getFirst();
		}
	}

	private StrongholdEstimator() {
	}

	/**
	 * @param sameStronghold all throws point at the same stronghold (they were made close together)
	 * @return null when no layout fits at all
	 */
	public static Result estimate(List<Throw> throwsList, int hypotheses, double sigmaDeg, boolean sameStronghold, int top) {
		if (throwsList.isEmpty() || hypotheses <= 0 || sigmaDeg <= 0 || top <= 0) return null;
		double twoSigmaSq = 2 * sigmaDeg * sigmaDeg;
		int[] xs = new int[STRONGHOLDS], zs = new int[STRONGHOLDS];
		int[] nearestCounts = new int[STRONGHOLDS];
		long[] chunks = new long[hypotheses];
		double[] logWeights = new double[hypotheses];
		int scored = 0;
		double maxLog = Double.NEGATIVE_INFINITY;

		for (int h = 0; h < hypotheses; h++) {
			layout(SEED + h, xs, zs);
			java.util.Arrays.fill(nearestCounts, 0);
			double logWeight = 0;
			int first = -1;
			boolean valid = true;
			for (Throw t : throwsList) {
				int nearest = nearest(xs, zs, t.x, t.z);
				if (first == -1) first = nearest;
				if (sameStronghold && nearest != first) {
					valid = false;
					break;
				}
				nearestCounts[nearest]++;
				double predicted = yaw(chunkCenter(xs[nearest]) - t.x, chunkCenter(zs[nearest]) - t.z);
				double delta = wrapDegrees(t.yaw - predicted);
				logWeight -= delta * delta / twoSigmaSq;
			}
			if (!valid) continue;
			int chosen = first;
			if (!sameStronghold) {
				for (int i = 0; i < STRONGHOLDS; i++) if (nearestCounts[i] > nearestCounts[chosen]) chosen = i;
			}
			chunks[scored] = pack(xs[chosen], zs[chosen]);
			logWeights[scored] = logWeight;
			maxLog = Math.max(maxLog, logWeight);
			scored++;
		}
		if (scored == 0) return null;

		Map<Long, Double> perChunk = new HashMap<>();
		double sum = 0;
		for (int i = 0; i < scored; i++) {
			double weight = Math.exp(logWeights[i] - maxLog);
			perChunk.merge(chunks[i], weight, Double::sum);
			sum += weight;
		}
		if (sum <= 0) return null;
		List<Candidate> candidates = new ArrayList<>(perChunk.size());
		for (var entry : perChunk.entrySet()) {
			long key = entry.getKey();
			candidates.add(new Candidate((int) (key >> 32), (int) key, entry.getValue() / sum));
		}
		candidates.sort((a, b) -> Double.compare(b.probability, a.probability));
		return new Result(List.copyOf(candidates.subList(0, Math.min(top, candidates.size()))), throwsList.size());
	}

	/** One random stronghold layout, the way the game rings them around 0,0 (chunk coordinates). */
	static void layout(long seed, int[] xs, int[] zs) {
		SplittableRandom random = new SplittableRandom(seed);
		double angle = random.nextDouble() * Math.PI * 2;
		int ring = 0, spread = SPREAD, inRing = 0;
		for (int i = 0; i < STRONGHOLDS; i++) {
			double radius = 4 * DISTANCE + DISTANCE * ring * 6 + (random.nextDouble() - 0.5) * (DISTANCE * 2.5);
			xs[i] = (int) Math.round(Math.cos(angle) * radius) + random.nextInt(-SNAP, SNAP + 1);
			zs[i] = (int) Math.round(Math.sin(angle) * radius) + random.nextInt(-SNAP, SNAP + 1);
			angle += Math.PI * 2 / spread;
			if (++inRing == spread) {
				ring++;
				inRing = 0;
				spread += 2 * spread / (ring + 1);
				spread = Math.min(spread, STRONGHOLDS - i);
				angle += random.nextDouble() * Math.PI * 2;
			}
		}
	}

	/** Index of the stronghold nearest to block {@code x, z}. */
	static int nearest(int[] xs, int[] zs, double x, double z) {
		int best = 0;
		double bestDistance = Double.MAX_VALUE;
		for (int i = 0; i < xs.length; i++) {
			double dx = chunkCenter(xs[i]) - x, dz = chunkCenter(zs[i]) - z;
			double distance = dx * dx + dz * dz;
			if (distance < bestDistance) {
				bestDistance = distance;
				best = i;
			}
		}
		return best;
	}

	/** Minecraft yaw (degrees, 0 = south, 90 = west) of a horizontal direction. */
	public static double yaw(double dx, double dz) {
		return wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90);
	}

	static double wrapDegrees(double degrees) {
		double d = degrees % 360;
		if (d >= 180) d -= 360;
		if (d < -180) d += 360;
		return d;
	}

	private static double chunkCenter(int chunk) {
		return chunk * 16.0 + 8;
	}

	private static long pack(int x, int z) {
		return (long) x << 32 | z & 0xFFFFFFFFL;
	}
}
