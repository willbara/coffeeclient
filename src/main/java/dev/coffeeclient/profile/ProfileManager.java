package dev.coffeeclient.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.util.JsonFiles;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Named configuration snapshots. "Save current" captures the live config into a profile; "Load"
 * applies a profile back over the live config. The active profile is remembered for display.
 *
 * <p>Profiles are shared as real config files: {@link #export(Profile)} writes the profile's config to
 * {@code config/coffeeclient/profiles/<name>.json} and {@link #importFile(Path)} reads one back. The file
 * is a plain configuration document (the same shape as {@code config.json}), so it can also be dropped
 * straight into another instance as its config.
 */
public final class ProfileManager {
	private final Path file;
	private final List<Profile> profiles = new ArrayList<>();
	private String active;
	private int revision;

	public ProfileManager(Path file) {
		this.file = file;
	}

	public List<Profile> all() {
		return profiles;
	}

	public int revision() {
		return revision;
	}

	public String active() {
		return active;
	}

	/** Capture the live configuration into a profile (creating it or overwriting it). */
	public Profile capture(String name) {
		String clean = (name == null || name.isBlank()) ? "Profile" : name.trim();
		Profile profile = byName(clean);
		if (profile == null) {
			profile = new Profile(unique(clean), CoffeeClient.config().snapshot());
			profiles.add(profile);
		} else {
			profile.setConfig(CoffeeClient.config().snapshot());
		}
		active = profile.name();
		save();
		return profile;
	}

	/** Apply a profile's configuration to the live config. */
	public void apply(Profile profile) {
		if (profile == null) return;
		CoffeeClient.config().applySnapshot(profile.config().deepCopy());
		active = profile.name();
		save();
	}

	public void rename(Profile profile, String name) {
		if (profile == null || name == null || name.isBlank()) return;
		String clean = name.trim();
		if (!clean.equalsIgnoreCase(profile.name()) && hasName(clean)) return;
		profile.rename(clean);
		if (profile.name().equals(active)) active = profile.name();
		save();
	}

	public void setNotes(Profile profile, String notes) {
		if (profile == null) return;
		profile.setNotes(notes);
		save();
	}

	public void setServers(Profile profile, String servers) {
		if (profile == null) return;
		profile.setServers(servers);
		save();
	}

	/** On joining {@code address}: loads the profile bound to its domain, when that isn't already active. */
	public void onJoin(String address) {
		if (address == null) return;
		String root = rootDomain(address);
		for (Profile profile : profiles) {
			for (String server : profile.servers().split(",")) {
				if (server.isBlank() || !rootDomain(server).equals(root)) continue;
				if (!profile.name().equals(active)) {
					apply(profile);
					CoffeeClient.hud().toast("Loaded profile " + profile.name() + " for " + root, CoffeeClient.theme().accent);
				}
				return;
			}
		}
	}

	/** "Play.Example.com:25565" and "example.com" both give "example.com"; IP addresses stay whole. */
	static String rootDomain(String address) {
		String host = address.trim().toLowerCase(java.util.Locale.ROOT);
		int colon = host.lastIndexOf(':');
		if (colon > 0 && host.indexOf(':') == colon) host = host.substring(0, colon);
		if (host.matches("[0-9.]+") || host.contains(":")) return host;
		String[] parts = host.split("\\.");
		return parts.length <= 2 ? host : parts[parts.length - 2] + "." + parts[parts.length - 1];
	}

	public void remove(Profile profile) {
		if (profiles.remove(profile)) {
			if (profile.name().equals(active)) active = null;
			save();
		}
	}

	public Profile byName(String name) {
		if (name == null) return null;
		for (Profile profile : profiles) if (profile.name().equalsIgnoreCase(name)) return profile;
		return null;
	}

	private boolean hasName(String name) {
		return byName(name) != null;
	}

	private String unique(String base) {
		String candidate = base;
		int i = 2;
		while (hasName(candidate)) candidate = base + " " + i++;
		return candidate;
	}

	// ---- sharing ----

	/** Folder that holds profiles exported to disk; also the folder scanned for profiles to import. */
	public Path exportDirectory() {
		return CoffeeClient.config().directory().resolve("profiles");
	}

	/** The {@code .json} files currently sitting in the export folder, sorted by name. */
	public List<Path> exportedFiles() {
		Path dir = exportDirectory();
		if (!Files.isDirectory(dir)) return List.of();
		try (Stream<Path> stream = Files.list(dir)) {
			return stream
				.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
				.sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
				.toList();
		} catch (IOException e) {
			CoffeeClient.LOG.warn("Could not list {}: {}", dir, e.toString());
			return List.of();
		}
	}

	/**
	 * Write a profile's configuration into the export folder as a plain config file. The document is the
	 * same shape as {@code config.json}, so the file doubles as an importable config for another instance.
	 */
	public Path export(Profile profile) throws IOException {
		Path dir = exportDirectory();
		Files.createDirectories(dir);
		Path out = dir.resolve(sanitize(profile.name()) + ".json");
		JsonFiles.writeAtomic(out, JsonFiles.GSON.toJson(profile.config()));
		return out;
	}

	/**
	 * Read a config file (one written by {@link #export(Profile)}, or a wrapper with {@code name}/{@code
	 * config}) and add it as a new profile, picking a free name from the file's name. Returns the new
	 * profile, or {@code null} if the file is missing or not a JSON object.
	 */
	public @Nullable Profile importFile(Path file) {
		JsonObject root = JsonFiles.read(file);
		if (root == null) return null;

		JsonObject config = root;
		String name = stripExtension(file.getFileName().toString());
		if (root.has("config") && root.get("config").isJsonObject()) {
			config = root.getAsJsonObject("config");
			if (root.has("name") && root.get("name").isJsonPrimitive()) name = root.get("name").getAsString();
		}

		Profile profile = new Profile(unique(name), config.deepCopy());
		profiles.add(profile);
		save();
		return profile;
	}

	/** Keep a profile name usable as a file name. */
	private static String sanitize(String name) {
		String clean = name == null ? "" : name.trim();
		clean = clean.replaceAll("[\\\\/:*?\"<>|]", "_");
		if (clean.isEmpty()) clean = "Profile";
		return clean.length() > 48 ? clean.substring(0, 48) : clean;
	}

	private static String stripExtension(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot > 0 ? fileName.substring(0, dot) : fileName;
	}

	// ---- persistence ----

	public void load() {
		JsonObject root = JsonFiles.read(file);
		if (root != null) {
			if (root.has("active") && root.get("active").isJsonPrimitive()) active = root.get("active").getAsString();
			JsonElement arr = root.get("profiles");
			if (arr != null && arr.isJsonArray()) {
				for (JsonElement el : arr.getAsJsonArray()) {
					try {
						JsonObject o = el.getAsJsonObject();
						String name = o.get("name").getAsString();
						JsonObject config = o.has("config") && o.get("config").isJsonObject() ? o.getAsJsonObject("config") : new JsonObject();
						Profile profile = new Profile(name, config);
						if (o.has("notes") && o.get("notes").isJsonPrimitive()) profile.setNotes(o.get("notes").getAsString());
						if (o.has("servers") && o.get("servers").isJsonPrimitive()) profile.setServers(o.get("servers").getAsString());
						profiles.add(profile);
					} catch (RuntimeException e) {
						CoffeeClient.LOG.warn("Skipping malformed profile: {}", e.toString());
					}
				}
			}
		}
		if (profiles.isEmpty()) {
			Profile defaults = new Profile("Default", CoffeeClient.config().snapshot());
			profiles.add(defaults);
			active = defaults.name();
			save();
		}
		if (active == null || byName(active) == null) active = profiles.getFirst().name();
	}

	private void save() {
		revision++;
		JsonObject root = new JsonObject();
		if (active != null) root.addProperty("active", active);
		JsonArray arr = new JsonArray();
		for (Profile profile : profiles) {
			JsonObject o = new JsonObject();
			o.addProperty("name", profile.name());
			o.add("config", profile.config());
			if (!profile.notes().isEmpty()) o.addProperty("notes", profile.notes());
			if (!profile.servers().isEmpty()) o.addProperty("servers", profile.servers());
			arr.add(o);
		}
		root.add("profiles", arr);
		try {
			JsonFiles.writeAtomic(file, JsonFiles.GSON.toJson(root));
		} catch (Exception e) {
			CoffeeClient.LOG.warn("Could not save profiles: {}", e.toString());
		}
	}
}
