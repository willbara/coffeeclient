package dev.coffeeclient.account;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.util.JsonFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.Util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Saved accounts ({@code accounts.json}, secrets sealed by {@link Vault}) and switching between them.
 * The account the game was launched with is remembered so you can switch back. Logins that need the
 * network run on an IO thread; the session itself is swapped on the client thread.
 */
public final class AccountManager {
	private final Path file;
	private final List<Account> accounts = new ArrayList<>();
	/** Saved entries the vault key cannot open, written back as they were so another key can still read them. */
	private final List<JsonObject> unreadable = new ArrayList<>();
	private User original;
	private Account current;
	private int revision;

	public AccountManager(Path file) {
		this.file = file;
	}

	public int revision() {
		return revision;
	}

	public List<Account> all() {
		return List.copyOf(accounts);
	}

	/** The saved account in use, or null while on the launch account. */
	public Account current() {
		return current;
	}

	public String currentName() {
		return Minecraft.getInstance().getUser().getName();
	}

	public boolean onOriginal() {
		return current == null;
	}

	public String originalName() {
		return original == null ? Minecraft.getInstance().getUser().getName() : original.getName();
	}

	/** Adds an offline account; returns null when the name is not a valid Minecraft name. */
	public Account addOffline(String name) {
		String clean = name.trim();
		if (!clean.matches("[A-Za-z0-9_]{1,16}")) return null;
		Account existing = find(Account.Type.OFFLINE, clean);
		if (existing != null) return existing;
		return add(new Account(Account.Type.OFFLINE, clean, UUIDUtil.createOfflinePlayerUUID(clean), ""));
	}

	/** Checks a pasted Minecraft access token and saves it as an account (IO thread). */
	public CompletableFuture<Account> addToken(String token) {
		String clean = token.trim().replaceFirst("(?i)^bearer\\s+", "");
		return CompletableFuture.supplyAsync(() -> {
			try {
				return MicrosoftAuth.profile(clean);
			} catch (MicrosoftAuth.AuthException e) {
				throw new IllegalStateException(e.getMessage());
			}
		}, Util.ioPool()).thenApplyAsync(profile -> {
			Account existing = find(Account.Type.TOKEN, profile.name());
			if (existing != null) accounts.remove(existing);
			return add(new Account(Account.Type.TOKEN, profile.name(), profile.uuid(), clean));
		}, Minecraft.getInstance());
	}

	/**
	 * Saves the account a finished Microsoft device-code login produced and switches to it (its session
	 * is fresh). Client thread.
	 */
	public Account addMicrosoft(MicrosoftAuth.Login login) {
		Account existing = findUuid(Account.Type.MICROSOFT, login.uuid());
		if (existing != null) accounts.remove(existing);
		Account account = add(new Account(Account.Type.MICROSOFT, login.name(), login.uuid(), login.refreshToken()));
		if (Minecraft.getInstance().level == null) {
			use(account, new User(login.name(), login.uuid(), login.accessToken(), Optional.empty(), Optional.empty()), true);
		}
		return account;
	}

	private Account add(Account account) {
		accounts.add(account);
		changed();
		return account;
	}

	public void remove(Account account) {
		if (accounts.remove(account)) changed();
	}

	/**
	 * Switches the game to {@code account}. Microsoft accounts refresh their token first, so the future
	 * fails with the reason when Microsoft refuses it. Completes on the client thread.
	 */
	public CompletableFuture<Void> login(Account account) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) return CompletableFuture.failedFuture(new IllegalStateException("Leave the world first."));
		return switch (account.type) {
			case OFFLINE -> CompletableFuture.runAsync(() -> use(account,
				new User(account.name, account.uuid, "0", Optional.empty(), Optional.empty()), false), mc);
			case TOKEN -> CompletableFuture.supplyAsync(() -> {
				try {
					return MicrosoftAuth.profile(account.secret);
				} catch (MicrosoftAuth.AuthException e) {
					throw new IllegalStateException(e.getMessage());
				}
			}, Util.ioPool()).thenAcceptAsync(profile -> {
				account.name = profile.name();
				use(account, new User(profile.name(), profile.uuid(), account.secret, Optional.empty(), Optional.empty()), true);
			}, mc);
			case MICROSOFT -> CompletableFuture.supplyAsync(() -> {
				try {
					return MicrosoftAuth.refresh(account.secret);
				} catch (MicrosoftAuth.AuthException e) {
					throw new IllegalStateException(e.getMessage());
				}
			}, Util.ioPool()).thenAcceptAsync(login -> {
				account.name = login.name();
				account.uuid = login.uuid();
				account.secret = login.refreshToken();
				use(account, new User(login.name(), login.uuid(), login.accessToken(), Optional.empty(), Optional.empty()), true);
			}, mc);
		};
	}

	/** Back to the account the game was launched with. */
	public void restoreOriginal() {
		if (original == null || current == null || Minecraft.getInstance().level != null) return;
		current = null;
		Session.apply(original, !original.getAccessToken().isEmpty() && !"0".equals(original.getAccessToken()));
		revision++;
	}

	private void use(Account account, User user, boolean online) {
		// The launch account, kept before the first switch so it can be restored.
		if (original == null) original = Minecraft.getInstance().getUser();
		Session.apply(user, online);
		current = account;
		account.lastUsed = System.currentTimeMillis();
		changed();
	}

	private Account find(Account.Type type, String name) {
		for (Account a : accounts) if (a.type == type && a.name.equalsIgnoreCase(name)) return a;
		return null;
	}

	private Account findUuid(Account.Type type, UUID uuid) {
		for (Account a : accounts) if (a.type == type && a.uuid.equals(uuid)) return a;
		return null;
	}

	private void changed() {
		revision++;
		save();
	}

	public void load() {
		JsonObject root = JsonFiles.read(file);
		if (root == null || !(root.get("accounts") instanceof JsonArray array)) return;
		for (JsonElement element : array) {
			try {
				JsonObject o = element.getAsJsonObject();
				Account.Type type = Account.Type.valueOf(o.get("type").getAsString().toUpperCase(Locale.ROOT));
				String secret = Vault.open(o.has("secret") ? o.get("secret").getAsString() : "");
				if (secret == null) {
					unreadable.add(o);
					continue;
				}
				Account account = new Account(type, o.get("name").getAsString(), UUID.fromString(o.get("uuid").getAsString()), secret);
				account.lastUsed = o.has("lastUsed") ? o.get("lastUsed").getAsLong() : 0;
				accounts.add(account);
			} catch (RuntimeException e) {
				CoffeeClient.LOG.warn("[Accounts] Skipping a bad entry: {}", e.toString());
			}
		}
		if (!unreadable.isEmpty()) CoffeeClient.LOG.warn("[Accounts] {} saved logins could not be decrypted (vault key changed?); they are kept in the file", unreadable.size());
		revision++;
	}

	private void save() {
		JsonArray array = new JsonArray();
		for (Account a : accounts) {
			JsonObject o = new JsonObject();
			o.addProperty("type", a.type.name());
			o.addProperty("name", a.name);
			o.addProperty("uuid", a.uuid.toString());
			o.addProperty("secret", Vault.seal(a.secret));
			o.addProperty("lastUsed", a.lastUsed);
			array.add(o);
		}
		unreadable.forEach(array::add);
		JsonObject root = new JsonObject();
		root.addProperty("version", 1);
		root.add("accounts", array);
		try {
			JsonFiles.writeAtomic(file, JsonFiles.GSON.toJson(root));
		} catch (Exception e) {
			CoffeeClient.LOG.warn("[Accounts] Could not save accounts: {}", e.toString());
		}
	}
}
