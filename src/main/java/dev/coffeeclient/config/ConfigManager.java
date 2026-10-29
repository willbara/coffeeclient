package dev.coffeeclient.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.KeybindSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.Async;
import dev.coffeeclient.util.JsonFiles;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * Persists modules (enabled, keybind, settings), mod options, theme and HUD layout to
 * {@code config/coffeeclient/config.json}.
 *
 * <p>Saving is debounced: any change calls {@link #markDirty()}, and ~750 ms after the last change the
 * JSON is built on the client thread and written on the I/O thread.
 */
public final class ConfigManager {
	private static final long DEBOUNCE_MS = 750;
	/** 2: key codes are GLFW (26.2). 1 was written by the 26.3 build, with SDL key codes. */
	private static final int VERSION = 2;

	private static volatile boolean ready;
	private static ConfigManager instance;

	private final Path directory = FabricLoader.getInstance().getConfigDir().resolve(CoffeeClient.MOD_ID);
	private final Path file = directory.resolve("config.json");
	private ScheduledFuture<?> pendingSave;

	public ConfigManager() {
		instance = this;
	}

	public Path directory() {
		return directory;
	}

	/** Request a (debounced) save. No-op until the initial load has finished. */
	public static void markDirty() {
		if (ready && instance != null) instance.scheduleSave();
	}

	private synchronized void scheduleSave() {
		if (pendingSave != null) pendingSave.cancel(false);
		pendingSave = Async.ioLater(() -> Minecraft.getInstance().execute(this::saveAsync), DEBOUNCE_MS);
	}

	private void saveAsync() {
		// The tree must be built on the client thread (it reads live settings); stringifying it need not be.
		JsonObject tree = serialize();
		Async.io(() -> write(JsonFiles.GSON.toJson(tree)));
	}

	/** Synchronous save for shutdown. */
	public void saveNow() {
		synchronized (this) {
			if (pendingSave != null) pendingSave.cancel(false);
		}
		write(JsonFiles.GSON.toJson(serialize()));
	}

	private void write(String json) {
		try {
			JsonFiles.writeAtomic(file, json);
		} catch (Exception e) {
			CoffeeClient.LOG.error("Failed to save {}", file, e);
		}
	}

	// ---- load ----

	public void load() {
		JsonObject root = JsonFiles.read(file);
		if (root == null) {
			CoffeeClient.LOG.info("No config at {}, using defaults", file);
		} else {
			apply(root, false);
		}
		ready = true;
		// Rewrite once so translated 26.3 key codes are not translated again next launch.
		if (root != null && version(root) < VERSION) markDirty();
	}

	private static int version(JsonObject root) {
		JsonElement v = root.get("version");
		return v != null && v.isJsonPrimitive() ? v.getAsInt() : 1;
	}

	/** Snapshot the current configuration (theme, options, modules, HUD) for a profile. */
	public JsonObject snapshot() {
		return serialize();
	}

	/**
	 * Apply a captured snapshot over the live configuration and schedule a save. In a world, modules
	 * switch through their enable and disable hooks; in the menus they are restored as at launch.
	 */
	public void applySnapshot(JsonObject root) {
		ready = false;
		try {
			apply(root, Minecraft.getInstance().level != null);
		} finally {
			ready = true;
		}
		markDirty();
	}

	/** Reset everything to defaults (Settings → Reset all settings). */
	public void resetAll() {
		ready = false;
		try {
			CoffeeClient.theme().loading(true);
			try {
				CoffeeClient.theme().persistedSettings().forEach(Setting::reset);
			} finally {
				CoffeeClient.theme().loading(false);
			}
			CoffeeClient.options().all().forEach(Setting::reset);
			for (Module m : CoffeeClient.modules().all()) {
				m.ownSettings().forEach(Setting::reset);
				m.keybind().reset();
				m.shownInList().reset();
				m.restoreFavorite(false);
			}
			CoffeeClient.hud().resetLayout();
		} finally {
			ready = true;
		}
		markDirty();
	}

	private void apply(JsonObject root, boolean live) {
		KeybindSetting.readingLegacyCodes(version(root) < 2);
		try {
			applyTree(root, live);
		} finally {
			KeybindSetting.readingLegacyCodes(false);
		}
	}

	/** @param live switch modules with their hooks (after their settings), not just restore the flag */
	private void applyTree(JsonObject root, boolean live) {
		JsonObject themeJson = object(root, "theme");
		// switching presets while reading must not store the (not yet loaded) live look as the old theme's
		CoffeeClient.theme().loading(true);
		try {
			readSettings(root, "theme", CoffeeClient.theme().persistedSettings());
			CoffeeClient.theme().migrateLegacy(themeJson);
		} finally {
			CoffeeClient.theme().loading(false);
		}
		// Interface scale used to be a theme setting; carry a saved value into the universal option.
		JsonElement oldScale = themeJson == null ? null : themeJson.get(CoffeeClient.options().interfaceScale.name());
		if (oldScale != null) CoffeeClient.options().interfaceScale.fromJson(oldScale);
		readSettings(root, "options", CoffeeClient.options().all());
		JsonObject modules = object(root, "modules");
		if (modules != null) {
			// XCarry moved from InventoryTweaks (now Organizer) to Inventory+: carry a saved value over.
			JsonObject oldTweaks = object(object(modules, "InventoryTweaks"), "settings");
			JsonObject plus = object(object(modules, "Inventory+"), "settings");
			if (oldTweaks != null && plus != null && oldTweaks.has("XCarry") && !plus.has("XCarry")) {
				plus.add("XCarry", oldTweaks.get("XCarry"));
			}
			for (Module m : CoffeeClient.modules().all()) {
				JsonObject mj = object(modules, m.name());
				// A renamed module keeps its old name as an alias, so its saved settings still load.
				for (int i = 0; mj == null && i < m.aliases().size(); i++) mj = object(modules, m.aliases().get(i));
				if (mj == null) continue;
				try {
					Boolean enabled = mj.has("enabled") ? mj.get("enabled").getAsBoolean() : null;
					if (enabled != null && !live) m.restoreEnabled(enabled);
					if (mj.has("favorite")) m.restoreFavorite(mj.get("favorite").getAsBoolean());
					if (mj.has("keybind")) m.keybind().fromJson(mj.get("keybind"));
					if (mj.has("hidden")) m.shownInList().set(!mj.get("hidden").getAsBoolean());
					readSettings(mj, "settings", m.ownSettings());
					// onEnable sees the profile's settings, and a module the blocklist forbids here stays off.
					if (enabled != null && live) m.setEnabledSilently(enabled);
				} catch (RuntimeException e) {
					CoffeeClient.LOG.warn("Ignoring bad config for module {}: {}", m.name(), e.toString());
				}
			}
		}
		JsonObject hud = object(root, "hud");
		if (hud != null) CoffeeClient.hud().fromJson(hud);
	}

	private static void readSettings(JsonObject parent, String key, List<Setting<?>> settings) {
		JsonObject obj = object(parent, key);
		if (obj == null) return;
		for (Setting<?> s : settings) {
			JsonElement value = obj.get(s.name());
			if (value != null) s.fromJson(value);
		}
	}

	private static JsonObject object(JsonObject parent, String key) {
		JsonElement el = parent == null ? null : parent.get(key);
		return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
	}

	// ---- save ----

	private JsonObject serialize() {
		JsonObject root = new JsonObject();
		root.addProperty("version", VERSION);
		root.add("theme", writeSettings(CoffeeClient.theme().persistedSettings()));
		root.add("options", writeSettings(CoffeeClient.options().all()));
		JsonObject modules = new JsonObject();
		for (Module m : CoffeeClient.modules().all()) {
			JsonObject mj = new JsonObject();
			if (m.isToggleable()) mj.addProperty("enabled", m.isEnabled());
			if (m.favorite()) mj.addProperty("favorite", true);
			mj.add("keybind", m.keybind().toJson());
			if (!m.shownInList().get()) mj.addProperty("hidden", true);
			List<Setting<?>> own = m.ownSettings();
			if (!own.isEmpty()) mj.add("settings", writeSettings(own));
			modules.add(m.name(), mj);
		}
		root.add("modules", modules);
		root.add("hud", CoffeeClient.hud().toJson());
		return root;
	}

	private static JsonObject writeSettings(List<Setting<?>> settings) {
		JsonObject obj = new JsonObject();
		for (Setting<?> s : settings) obj.add(s.name(), s.toJson());
		return obj;
	}
}
