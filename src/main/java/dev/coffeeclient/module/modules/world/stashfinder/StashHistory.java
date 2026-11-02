package dev.coffeeclient.module.modules.world.stashfinder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.util.JsonFiles;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Saved stash and detector-find history for one server. Stashes keep chunk coordinates (as the old
 * file did); detector finds keep block coordinates and one row per chunk and kind.
 */
public final class StashHistory {
	/** How far a remembered find has been checked: new, seen, or cleared out. */
	public enum State {
		NEW, VISITED, CLEARED;

		public State next() {
			State[] values = values();
			return values[(ordinal() + 1) % values.length];
		}
	}

	/** A container stash chunk. {@code x}/{@code z} are chunk coordinates. */
	public record Stash(String dimension, int x, int z, int y, int total, Map<String, Integer> counts,
						long firstSeen, long lastSeen, State state) {
		public int centerX() {
			return x * 16 + 8;
		}

		public int centerZ() {
			return z * 16 + 8;
		}

		public String key() {
			return stashKey(dimension, x, z);
		}
	}

	/** One kind of detector find in a chunk. {@code x}/{@code y}/{@code z} are block coordinates. */
	public record Find(String dimension, Kind kind, int x, int y, int z, int count,
					   long firstSeen, long lastSeen, State state) {
		public String key() {
			return findKey(dimension, kind, x >> 4, z >> 4);
		}
	}

	public record Loaded(Map<String, Stash> stashes, Map<String, Find> finds) {
		public boolean isEmpty() {
			return stashes.isEmpty() && finds.isEmpty();
		}
	}

	private StashHistory() {
	}

	public static String stashKey(String dimension, int chunkX, int chunkZ) {
		return dimension + "|" + chunkX + "," + chunkZ;
	}

	public static String findKey(String dimension, Kind kind, int chunkX, int chunkZ) {
		return dimension + "|" + kind.name() + "|" + chunkX + "," + chunkZ;
	}

	/** Keep the current scan's contents but the earliest first-seen, latest last-seen and user state. */
	public static Stash merge(Stash loaded, Stash current) {
		return new Stash(current.dimension(), current.x(), current.z(), current.y(), current.total(), current.counts(),
			earliest(loaded.firstSeen(), current.firstSeen()),
			Math.max(loaded.lastSeen(), current.lastSeen()),
			current.state() == State.NEW ? loaded.state() : current.state());
	}

	public static Find merge(Find loaded, Find current) {
		return new Find(current.dimension(), current.kind(), current.x(), current.y(), current.z(), current.count(),
			earliest(loaded.firstSeen(), current.firstSeen()),
			Math.max(loaded.lastSeen(), current.lastSeen()),
			current.state() == State.NEW ? loaded.state() : current.state());
	}

	private static long earliest(long a, long b) {
		if (a <= 0) return b;
		if (b <= 0) return a;
		return Math.min(a, b);
	}

	public static String toJson(Map<String, Stash> stashes, Map<String, Find> finds) {
		JsonObject root = new JsonObject();
		JsonArray stashArray = new JsonArray();
		for (Stash stash : stashes.values()) {
			JsonObject object = new JsonObject();
			object.addProperty("dim", stash.dimension());
			object.addProperty("x", stash.x());
			object.addProperty("z", stash.z());
			object.addProperty("y", stash.y());
			object.addProperty("total", stash.total());
			object.addProperty("firstSeen", stash.firstSeen());
			object.addProperty("lastSeen", stash.lastSeen());
			object.addProperty("state", stash.state().name());
			JsonObject counts = new JsonObject();
			stash.counts().forEach(counts::addProperty);
			object.add("counts", counts);
			stashArray.add(object);
		}
		root.add("stashes", stashArray);

		JsonArray findArray = new JsonArray();
		for (Find find : finds.values()) {
			JsonObject object = new JsonObject();
			object.addProperty("dim", find.dimension());
			object.addProperty("kind", find.kind().name());
			object.addProperty("x", find.x());
			object.addProperty("y", find.y());
			object.addProperty("z", find.z());
			object.addProperty("count", find.count());
			object.addProperty("firstSeen", find.firstSeen());
			object.addProperty("lastSeen", find.lastSeen());
			object.addProperty("state", find.state().name());
			findArray.add(object);
		}
		root.add("finds", findArray);
		return JsonFiles.GSON.toJson(root);
	}

	public static Loaded read(Path path) {
		Map<String, Stash> stashes = new LinkedHashMap<>();
		Map<String, Find> finds = new LinkedHashMap<>();
		JsonObject root = JsonFiles.read(path);
		if (root == null) return new Loaded(stashes, finds);
		int skipped = 0;
		if (root.has("stashes") && root.get("stashes").isJsonArray()) {
			for (JsonElement element : root.getAsJsonArray("stashes")) {
				if (!element.isJsonObject()) {
					skipped++;
					continue;
				}
				try {
					JsonObject object = element.getAsJsonObject();
					String dimension = object.get("dim").getAsString();
					int x = object.get("x").getAsInt();
					int z = object.get("z").getAsInt();
					int y = object.has("y") ? object.get("y").getAsInt() : 64;
					Map<String, Integer> counts = new TreeMap<>();
					if (object.has("counts") && object.get("counts").isJsonObject()) {
						for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("counts").entrySet()) {
							counts.put(entry.getKey(), entry.getValue().getAsInt());
						}
					}
					int total = object.has("total") ? object.get("total").getAsInt()
						: counts.values().stream().mapToInt(Integer::intValue).sum();
					Stash stash = new Stash(dimension, x, z, y, total, Map.copyOf(counts),
						longField(object, "firstSeen"), longField(object, "lastSeen"), state(object));
					stashes.put(stash.key(), stash);
				} catch (RuntimeException e) {
					// skip a malformed row rather than losing the whole file
					skipped++;
				}
			}
		}
		if (root.has("finds") && root.get("finds").isJsonArray()) {
			for (JsonElement element : root.getAsJsonArray("finds")) {
				if (!element.isJsonObject()) {
					skipped++;
					continue;
				}
				try {
					JsonObject object = element.getAsJsonObject();
					String dimension = object.get("dim").getAsString();
					Kind kind = Kind.valueOf(object.get("kind").getAsString().toUpperCase(Locale.ROOT));
					int x = object.get("x").getAsInt();
					int y = object.get("y").getAsInt();
					int z = object.get("z").getAsInt();
					int count = object.has("count") ? object.get("count").getAsInt() : 1;
					Find find = new Find(dimension, kind, x, y, z, count,
						longField(object, "firstSeen"), longField(object, "lastSeen"), state(object));
					finds.put(find.key(), find);
				} catch (RuntimeException e) {
					skipped++;
				}
			}
		}
		if (skipped > 0) CoffeeClient.LOG.warn("[StashFinder] Skipped {} unreadable rows in {}", skipped, path.getFileName());
		return new Loaded(stashes, finds);
	}

	private static long longField(JsonObject object, String name) {
		if (!object.has(name)) return 0L;
		try {
			return object.get(name).getAsLong();
		} catch (RuntimeException e) {
			return 0L;
		}
	}

	private static State state(JsonObject object) {
		if (!object.has("state")) return State.NEW;
		try {
			return State.valueOf(object.get("state").getAsString().toUpperCase(Locale.ROOT));
		} catch (RuntimeException e) {
			return State.NEW;
		}
	}
}
