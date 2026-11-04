package dev.coffeeclient.module.modules.world.stashfinder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StashHistoryTest {
	@TempDir
	Path directory;

	@Test
	void readsOldStashFileWithoutTimestamps() throws IOException {
		Path file = directory.resolve("old.json");
		Files.writeString(file, """
			{"stashes":[{"dim":"minecraft:overworld","x":12,"z":-4,"total":7,
			"counts":{"minecraft:chest":5,"minecraft:barrel":2}}]}
			""");

		StashHistory.Loaded loaded = StashHistory.read(file);
		StashHistory.Stash stash = loaded.stashes().get(StashHistory.stashKey("minecraft:overworld", 12, -4));
		assertNotNull(stash);
		assertEquals(7, stash.total());
		assertEquals(Map.of("minecraft:chest", 5, "minecraft:barrel", 2), stash.counts());
		assertEquals(64, stash.y());
		assertEquals(0, stash.firstSeen());
		assertEquals(0, stash.lastSeen());
		assertEquals(StashHistory.State.NEW, stash.state());
		assertTrue(loaded.finds().isEmpty());
	}

	@Test
	void roundTripsStashesAndFinds() throws IOException {
		Map<String, Integer> counts = new TreeMap<>();
		counts.put("minecraft:chest", 5);
		counts.put("minecraft:barrel", 2);
		StashHistory.Stash stash = new StashHistory.Stash("minecraft:overworld", 12, -4, 31, 7, Map.copyOf(counts),
			1_700_000_000_000L, 1_700_000_060_000L, StashHistory.State.VISITED);
		StashHistory.Find find = new StashHistory.Find("minecraft:the_nether", Kind.POCKET, -27, 118, 42, 3,
			1_700_000_010_000L, 1_700_000_070_000L, StashHistory.State.CLEARED);

		Map<String, StashHistory.Stash> stashes = new LinkedHashMap<>();
		stashes.put(stash.key(), stash);
		Map<String, StashHistory.Find> finds = new LinkedHashMap<>();
		finds.put(find.key(), find);

		Path file = directory.resolve("history.json");
		Files.writeString(file, StashHistory.toJson(stashes, finds));
		StashHistory.Loaded loaded = StashHistory.read(file);

		assertEquals(stash, loaded.stashes().get(stash.key()));
		assertEquals(find, loaded.finds().get(find.key()));
	}

	@Test
	void mergeKeepsFirstSeenAndUserState() {
		StashHistory.Stash loaded = new StashHistory.Stash("minecraft:overworld", 0, 0, 20, 8,
			Map.of("minecraft:chest", 8), 100, 200, StashHistory.State.CLEARED);
		StashHistory.Stash current = new StashHistory.Stash("minecraft:overworld", 0, 0, 25, 10,
			Map.of("minecraft:chest", 10), 300, 400, StashHistory.State.NEW);

		StashHistory.Stash merged = StashHistory.merge(loaded, current);
		assertEquals(100, merged.firstSeen());
		assertEquals(400, merged.lastSeen());
		assertEquals(StashHistory.State.CLEARED, merged.state());
		assertEquals(10, merged.total());
		assertEquals(25, merged.y());
	}

	@Test
	void ageRoundTripsAndOldFilesLoadWithout() throws IOException {
		StashHistory.Stash aged = new StashHistory.Stash("minecraft:overworld", 3, 4, 40, 6,
			Map.of("minecraft:chest", 6), 100, 200, StashHistory.State.NEW, "old");
		Path file = directory.resolve("aged.json");
		Files.writeString(file, StashHistory.toJson(Map.of(aged.key(), aged), Map.of()));
		assertEquals(aged, StashHistory.read(file).stashes().get(aged.key()));

		// a file written before ages existed
		Path old = directory.resolve("old.json");
		Files.writeString(old, "{\"stashes\":[{\"dim\":\"minecraft:overworld\",\"x\":1,\"z\":2,\"total\":5,\"counts\":{\"minecraft:chest\":5}}]}");
		StashHistory.Stash loaded = StashHistory.read(old).stashes().values().iterator().next();
		assertEquals(null, loaded.age());

		// a rescan that has no verdict keeps the age already known
		StashHistory.Stash rescan = new StashHistory.Stash("minecraft:overworld", 3, 4, 40, 7,
			Map.of("minecraft:chest", 7), 300, 400, StashHistory.State.NEW);
		assertEquals("old", StashHistory.merge(aged, rescan).age());
	}

	@Test
	void csvHasOneColumnPerType() {
		StashHistory.Stash stash = new StashHistory.Stash("minecraft:overworld", 1, -1, 64, 7,
			Map.of("minecraft:chest", 5, "minecraft:barrel", 2), 0, 0, StashHistory.State.VISITED, "old-version");
		String csv = StashHistory.toCsv(java.util.List.of(stash), java.util.List.of("minecraft:barrel", "minecraft:chest", "minecraft:hopper"));
		String[] lines = csv.split("\n");
		assertEquals("dimension,x,y,z,total,minecraft:barrel,minecraft:chest,minecraft:hopper,age,state,first_seen,last_seen", lines[0]);
		assertEquals("minecraft:overworld,24,64,-8,7,2,5,0,old-version,visited,,", lines[1]);
	}
}
