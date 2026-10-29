package dev.coffeeclient.friend;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.util.JsonFiles;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The friend list, saved to {@code friends.json}. Session friends are not saved and are dropped when
 * you leave a world or server; timed friends lapse on their own. Lookups go through a name index, as
 * ESP asks for every player every frame. Client thread for edits.
 */
public final class FriendManager {
	private final Path file;
	private final List<Friend> friends = new ArrayList<>();
	/** Lower-case name and UUID string to friend, rebuilt on every edit. */
	private volatile Map<String, Friend> byName = Map.of();
	private int revision;
	private int ticks;

	/** Supplies the default for each rule (the Friends module's settings). */
	private Predicate<FriendTrait> defaults = trait -> true;

	public FriendManager(Path file) {
		this.file = file;
	}

	public void init(EventBus bus) {
		bus.subscribe(Events.GameLeft.class, e -> removeIf(Friend::temporary, f -> f.expiresAt == Friend.SESSION));
		bus.subscribe(Events.Tick.class, e -> {
			if (++ticks % 20 != 0) return;
			long now = System.currentTimeMillis();
			removeIf(f -> f.expired(now), f -> true);
		});
	}

	public void defaults(Predicate<FriendTrait> defaults) {
		this.defaults = defaults;
	}

	/** Changes on every edit, so screens know to rebuild. */
	public int revision() {
		return revision;
	}

	public List<Friend> all() {
		return List.copyOf(friends);
	}

	/** The active friend with this name (or UUID string), or null. */
	public Friend get(String name) {
		if (name == null) return null;
		Friend f = byName.get(name.toLowerCase(Locale.ROOT));
		return f == null || f.expired(System.currentTimeMillis()) ? null : f;
	}

	public boolean isFriend(String name) {
		return get(name) != null;
	}

	/** True when {@code name} is a friend and {@code trait} applies to them (their override, else the default). */
	public boolean has(String name, FriendTrait trait) {
		Friend f = get(name);
		if (f == null) return false;
		Boolean override = f.override(trait);
		return override != null ? override : defaults.test(trait);
	}

	/** Effective value of {@code trait} for {@code friend}. */
	public boolean effective(Friend friend, FriendTrait trait) {
		Boolean override = friend.override(trait);
		return override != null ? override : defaults.test(trait);
	}

	/** Adds a friend (or updates the expiry of an existing one); {@code expiresAt} as in {@link Friend}. */
	public Friend add(String name, UUID uuid, long expiresAt) {
		if (name == null || name.isBlank()) return null;
		Friend existing = get(name);
		if (existing != null) {
			// Re-adding someone permanent never makes them temporary.
			if (existing.temporary()) existing.expiresAt = expiresAt;
			if (uuid != null) existing.uuid(uuid);
			changed();
			return existing;
		}
		Friend f = new Friend(name.trim(), uuid, System.currentTimeMillis());
		f.expiresAt = expiresAt;
		friends.add(f);
		changed();
		return f;
	}

	public boolean add(String name) {
		return add(name, null, Friend.PERMANENT) != null;
		// oh man, I wish I had a permanent friend!
	}

	public boolean remove(String name) {
		return removeIf(f -> f.matches(name), f -> true);
	}

	public void remove(Friend friend) {
		if (friends.remove(friend)) changed();
	}

	public void clear() {
		friends.clear();
		changed();
	}

	/** Removes all temporary friends. */
	public void clearTemporary() {
		removeIf(Friend::temporary, f -> true);
	}

	public void setNote(Friend f, String note) {
		f.note = note == null ? "" : note.trim();
		changed();
	}

	public void setColor(Friend f, int rgb) {
		f.color = rgb;
		changed();
	}

	public void setExpiry(Friend f, long expiresAt) {
		f.expiresAt = expiresAt;
		changed();
	}

	/** null = follow the default. */
	public void setOverride(Friend f, FriendTrait trait, Boolean value) {
		if (value == null) f.overrides.remove(trait);
		else f.overrides.put(trait, value);
		changed();
	}

	private boolean removeIf(Predicate<Friend> match, Predicate<Friend> also) {
		boolean removed = friends.removeIf(f -> match.test(f) && also.test(f));
		if (removed) changed();
		return removed;
	}

	private void changed() {
		reindex();
		revision++;
		queueSave();
	}

	/** Cleared on the IO thread when the save is handed to the client thread, so a hand-off a disconnect drops cannot block later saves. */
	private volatile boolean saveQueued;

	/** Edits come in bursts (typing a note): write once they settle. */
	private void queueSave() {
		if (saveQueued) return;
		saveQueued = true;
		dev.coffeeclient.util.Async.ioLater(() -> {
			saveQueued = false;
			net.minecraft.client.Minecraft.getInstance().execute(this::saveAsync);
		}, 750);
	}

	// ---- persistence ----

	public void load() {
		JsonObject root = JsonFiles.read(file);
		if (root == null) return;
		JsonElement arr = root.get("friends");
		if (arr == null || !arr.isJsonArray()) return;
		long now = System.currentTimeMillis();
		for (JsonElement el : arr.getAsJsonArray()) {
			try {
				if (el.isJsonPrimitive()) {
					friends.add(new Friend(el.getAsString(), null, now));
					continue;
				}
				JsonObject o = el.getAsJsonObject();
				if (!o.has("name")) continue;
				UUID uuid = o.has("uuid") && !o.get("uuid").isJsonNull() ? UUID.fromString(o.get("uuid").getAsString()) : null;
				Friend f = new Friend(o.get("name").getAsString(), uuid, o.has("added") ? o.get("added").getAsLong() : now);
				if (o.has("note")) f.note = o.get("note").getAsString();
				if (o.has("color")) f.color = o.get("color").getAsInt();
				if (o.has("expires")) f.expiresAt = o.get("expires").getAsLong();
				if (o.has("rules")) {
					for (Map.Entry<String, JsonElement> rule : o.getAsJsonObject("rules").entrySet()) {
						try {
							f.overrides.put(FriendTrait.valueOf(rule.getKey()), rule.getValue().getAsBoolean());
						} catch (IllegalArgumentException ignored) {
							// a rule from a newer version
						}
					}
				}
				if (!f.expired(now)) friends.add(f);
			} catch (RuntimeException e) {
				CoffeeClient.LOG.warn("Skipping malformed friend: {}", e.toString());
			}
		}
		reindex();
		revision++;
	}

	private void reindex() {
		Map<String, Friend> index = new HashMap<>();
		for (Friend f : friends) {
			if (f.uuid() != null) index.put(f.uuid().toString(), f);
			index.put(f.name().toLowerCase(Locale.ROOT), f);
		}
		byName = index;
	}

	/** Moves names from the old Friends-module list into this store (once; the caller clears the old list). */
	public void importNames(List<String> names) {
		boolean any = false;
		for (String name : names) {
			if (name == null || name.isBlank() || get(name) != null) continue;
			friends.add(new Friend(name.trim(), null, System.currentTimeMillis()));
			any = true;
		}
		if (any) changed();
	}

	/** Writes friends.json now (also called on shutdown). */
	public void saveNow() {
		write(tree());
	}

	/** The tree is built on the client thread (it reads the live list); the write happens on the IO thread. */
	private void saveAsync() {
		JsonObject root = tree();
		dev.coffeeclient.util.Async.io(() -> write(root));
	}

	private JsonObject tree() {
		JsonObject root = new JsonObject();
		root.addProperty("version", 2);
		JsonArray arr = new JsonArray();
		for (Friend f : friends) {
			// Session friends end with the session.
			if (f.expiresAt == Friend.SESSION) continue;
			JsonObject o = new JsonObject();
			o.addProperty("name", f.name());
			if (f.uuid() != null) o.addProperty("uuid", f.uuid().toString());
			o.addProperty("added", f.addedAt);
			if (!f.note.isEmpty()) o.addProperty("note", f.note);
			if (f.color >= 0) o.addProperty("color", f.color);
			if (f.expiresAt != Friend.PERMANENT) o.addProperty("expires", f.expiresAt);
			if (!f.overrides.isEmpty()) {
				JsonObject rules = new JsonObject();
				f.overrides.forEach((trait, value) -> rules.addProperty(trait.name(), value));
				o.add("rules", rules);
			}
			arr.add(o);
		}
		root.add("friends", arr);
		return root;
	}

	private void write(JsonObject root) {
		try {
			JsonFiles.writeAtomic(file, JsonFiles.GSON.toJson(root));
		} catch (Exception e) {
			CoffeeClient.LOG.warn("Could not save friends: {}", e.toString());
			// can't save them all, but at least the in-memory list is still valid
		}
	}
}
