package dev.coffeeclient.module.modules.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.util.Async;
import dev.coffeeclient.util.JsonFiles;
import net.minecraft.core.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Remembered container contents for one server, one file per server under
 * {@code config/coffeeclient/chestsearch/}. Client thread only; writes are snapshotted here and
 * written atomically on the IO thread, debounced.
 */
final class ChestDatabase {
	/** One remembered stack. {@code nested} marks items found inside a shulker box or bundle. */
	record Item(String id, String name, int count, boolean nested) {
	}

	/**
	 * A container as last seen. {@code partner} is the other half of a double chest (or null); the
	 * ender chest is stored once per server with a null position.
	 */
	record Entry(BlockPos pos, BlockPos partner, String block, long time, List<Item> items) {
		/** Total count of items whose id, name or custom name contains {@code query} (lowercase). */
		int count(String query) {
			int total = 0;
			for (Item item : items) if (matches(item, query)) total += item.count();
			return total;
		}

		static boolean matches(Item item, String query) {
			return item.id().contains(query) || item.name().toLowerCase(Locale.ROOT).contains(query);
		}
	}

	private static final long SAVE_DELAY_MS = 2000;

	private final String server;
	private final Path file;
	/** Dimension id -> packed position -> entry. */
	private final Map<String, Map<Long, Entry>> containers = new HashMap<>();
	private Entry enderChest;
	/** Cleared on the IO thread when the save is handed to the client thread, so a hand-off a disconnect drops cannot block later saves. */
	private volatile boolean saveQueued;

	private ChestDatabase(String server, Path file) {
		this.server = server;
		this.file = file;
	}

	String server() {
		return server;
	}

	static ChestDatabase load(String server) {
		Path file = CoffeeClient.config().directory().resolve("chestsearch").resolve(server + ".json");
		ChestDatabase db = new ChestDatabase(server, file);
		if (!Files.exists(file)) return db;
		try {
			JsonObject root = JsonFiles.read(file);
			if (root == null) return db;
			if (root.has("dims")) {
				for (Map.Entry<String, JsonElement> dim : root.getAsJsonObject("dims").entrySet()) {
					Map<Long, Entry> map = db.containers.computeIfAbsent(dim.getKey(), k -> new HashMap<>());
					for (JsonElement e : dim.getValue().getAsJsonArray()) {
						Entry entry = readEntry(e.getAsJsonObject());
						if (entry != null && entry.pos() != null) map.put(entry.pos().asLong(), entry);
					}
				}
			}
			if (root.has("ender")) db.enderChest = readEntry(root.getAsJsonObject("ender"));
		} catch (Exception e) {
			CoffeeClient.LOG.warn("[ChestSearch] could not read {}: {}", file, e.toString());
		}
		return db;
	}

	Collection<Entry> in(String dimension) {
		Map<Long, Entry> map = containers.get(dimension);
		return map == null ? List.of() : map.values();
	}

	Entry at(String dimension, BlockPos pos) {
		Map<Long, Entry> map = containers.get(dimension);
		if (map == null) return null;
		Entry entry = map.get(pos.asLong());
		if (entry != null) return entry;
		// The other half of a double chest finds the entry stored at the half that was clicked.
		for (Entry e : map.values()) if (pos.equals(e.partner())) return e;
		return null;
	}

	Entry enderChest() {
		return enderChest;
	}

	int size() {
		int n = enderChest == null ? 0 : 1;
		for (Map<Long, Entry> map : containers.values()) n += map.size();
		return n;
	}

	void put(String dimension, Entry entry) {
		Map<Long, Entry> map = containers.computeIfAbsent(dimension, k -> new HashMap<>());
		// A double chest opened from its other half replaces the old entry instead of duplicating it.
		if (entry.partner() != null) map.remove(entry.partner().asLong());
		map.put(entry.pos().asLong(), entry);
		queueSave();
	}

	void putEnderChest(Entry entry) {
		enderChest = entry;
		queueSave();
	}

	void remove(String dimension, BlockPos pos) {
		Map<Long, Entry> map = containers.get(dimension);
		if (map != null && map.remove(pos.asLong()) != null) queueSave();
	}

	void clear() {
		containers.clear();
		enderChest = null;
		queueSave();
	}

	private void queueSave() {
		if (saveQueued) return;
		saveQueued = true;
		Async.ioLater(() -> {
			saveQueued = false;
			net.minecraft.client.Minecraft.getInstance().execute(this::save);
		}, SAVE_DELAY_MS);
	}

	/** Snapshot on the client thread, write on the IO thread. */
	void save() {
		saveQueued = false;
		JsonObject root = new JsonObject();
		JsonObject dims = new JsonObject();
		for (Map.Entry<String, Map<Long, Entry>> dim : containers.entrySet()) {
			JsonArray list = new JsonArray();
			for (Entry entry : dim.getValue().values()) list.add(writeEntry(entry));
			if (!list.isEmpty()) dims.add(dim.getKey(), list);
		}
		root.add("dims", dims);
		if (enderChest != null) root.add("ender", writeEntry(enderChest));
		String json = JsonFiles.GSON.toJson(root);
		Async.io(() -> {
			try {
				Files.createDirectories(file.getParent());
				JsonFiles.writeAtomic(file, json);
			} catch (Exception e) {
				CoffeeClient.LOG.warn("[ChestSearch] could not save {}: {}", file, e.toString());
			}
		});
	}

	private static JsonObject writeEntry(Entry entry) {
		JsonObject o = new JsonObject();
		if (entry.pos() != null) o.add("pos", pos(entry.pos()));
		if (entry.partner() != null) o.add("partner", pos(entry.partner()));
		o.addProperty("block", entry.block());
		o.addProperty("time", entry.time());
		JsonArray items = new JsonArray();
		for (Item item : entry.items()) {
			JsonObject i = new JsonObject();
			i.addProperty("id", item.id());
			i.addProperty("name", item.name());
			i.addProperty("count", item.count());
			if (item.nested()) i.addProperty("nested", true);
			items.add(i);
		}
		o.add("items", items);
		return o;
	}

	private static Entry readEntry(JsonObject o) {
		List<Item> items = new ArrayList<>();
		if (o.has("items")) {
			for (JsonElement e : o.getAsJsonArray("items")) {
				JsonObject i = e.getAsJsonObject();
				items.add(new Item(i.get("id").getAsString(), i.has("name") ? i.get("name").getAsString() : i.get("id").getAsString(),
					i.get("count").getAsInt(), i.has("nested") && i.get("nested").getAsBoolean()));
			}
		}
		return new Entry(o.has("pos") ? pos(o.getAsJsonArray("pos")) : null, o.has("partner") ? pos(o.getAsJsonArray("partner")) : null,
			o.has("block") ? o.get("block").getAsString() : "?", o.has("time") ? o.get("time").getAsLong() : 0, List.copyOf(items));
	}

	private static JsonArray pos(BlockPos pos) {
		JsonArray a = new JsonArray();
		a.add(pos.getX());
		a.add(pos.getY());
		a.add(pos.getZ());
		return a;
	}

	private static BlockPos pos(JsonArray a) {
		return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
	}
}
