package dev.coffeeclient.util;

/** The numbers behind Sonar's simulation readings, kept free of game classes so they can be tested. */
public final class ChunkActivity {
	/** Score needed for Low, Medium and High. */
	private static final double[] LEVELS = {10, 30, 70};

	private ChunkActivity() {
	}

	/** The score after {@code elapsedMs}: it halves every half window. */
	public static double decay(double score, long elapsedMs, double windowSeconds) {
		return score * Math.pow(0.5, elapsedMs / (windowSeconds * 500.0));
	}

	/** Index of the highest level the score reaches (0 = Low), or -1 below Low. */
	public static int level(double score) {
		int level = -1;
		for (int i = 0; i < LEVELS.length; i++) if (score >= LEVELS[i]) level = i;
		return level;
	}

	/** True for a chunk the server should not tick for you: past the simulation ring (plus a margin) but still in view. */
	public static boolean inOuterRing(int chunkDistance, int simulationDistance, int viewDistance) {
		return simulationDistance > 0 && chunkDistance >= simulationDistance + 2 && chunkDistance <= viewDistance;
	}
}
