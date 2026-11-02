package dev.coffeeclient.module.modules.world.stashfinder;

/**
 * One row of StashFinder's history panel: a stash chunk ({@code kind == null}) or a detector find.
 * Coordinates are block coordinates; {@code count} is containers or finds.
 */
public record HistoryRow(String key, String dimension, Kind kind, int x, int y, int z, int count,
						 String detail, long firstSeen, long lastSeen, StashHistory.State state) {
	public boolean stash() {
		return kind == null;
	}
}
