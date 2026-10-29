package dev.coffeeclient.account;

import java.util.UUID;

/**
 * A saved login. Microsoft accounts keep their refresh token, token accounts a Minecraft access token
 * (good for about a day); both are encrypted on disk by {@link Vault}. Offline accounts are a name
 * only, for offline-mode servers.
 */
public final class Account {
	public enum Type {
		MICROSOFT("Microsoft"), OFFLINE("Offline"), TOKEN("Token");

		public final String label;

		Type(String label) {
			this.label = label;
		}
	}

	public final Type type;
	String name;
	UUID uuid;
	/** Refresh token (Microsoft) or access token (Token); empty for offline accounts. Plain text in memory only. */
	String secret;
	long lastUsed;

	Account(Type type, String name, UUID uuid, String secret) {
		this.type = type;
		this.name = name;
		this.uuid = uuid;
		this.secret = secret == null ? "" : secret;
	}

	public String name() {
		return name;
	}

	public UUID uuid() {
		return uuid;
	}

	public long lastUsed() {
		return lastUsed;
	}
}
