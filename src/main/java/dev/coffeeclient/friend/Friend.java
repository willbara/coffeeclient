package dev.coffeeclient.friend;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * A friend: name (and UUID when known), an optional note and colour, how long the friendship lasts,
 * and per-friend overrides of the {@link FriendTrait} rules. Edit through {@link FriendManager} so
 * changes are saved.
 */
public final class Friend {
	/** {@link #expiresAt} value for a friend kept until you leave the world or server. */
	public static final long SESSION = -1;
	/** {@link #expiresAt} value for a permanent friend. */
	public static final long PERMANENT = 0;

	private final String name;
	private UUID uuid;
	String note = "";
	/** RGB, or -1 for each module's own friend colour. */
	int color = -1;
	long expiresAt = PERMANENT;
	final long addedAt;
	/** true = always, false = never; absent = follow the default for all friends. */
	final Map<FriendTrait, Boolean> overrides = new EnumMap<>(FriendTrait.class);

	Friend(String name, UUID uuid, long addedAt) {
		this.name = name;
		this.uuid = uuid;
		this.addedAt = addedAt;
	}

	public String name() {
		return name;
	}

	public UUID uuid() {
		return uuid;
	}

	void uuid(UUID uuid) {
		this.uuid = uuid;
	}

	public String note() {
		return note;
	}

	public int color() {
		return color;
	}

	public long expiresAt() {
		return expiresAt;
	}

	public long addedAt() {
		return addedAt;
	}

	public boolean temporary() {
		return expiresAt != PERMANENT;
	}

	public boolean expired(long now) {
		return expiresAt > 0 && now >= expiresAt;
	}

	/** The override for {@code trait}, or null when the default applies. */
	public Boolean override(FriendTrait trait) {
		return overrides.get(trait);
	}

	public boolean matches(String other) {
		if (other == null) return false;
		if (name.equalsIgnoreCase(other)) return true;
		return uuid != null && uuid.toString().equalsIgnoreCase(other);
	}
}
