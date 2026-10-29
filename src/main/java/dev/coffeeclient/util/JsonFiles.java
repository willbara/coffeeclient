package dev.coffeeclient.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.coffeeclient.core.CoffeeClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Tolerant JSON file IO: missing or corrupt files yield {@code null} (corrupt ones are set aside). */
public final class JsonFiles {
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	/** One write at a time, so two saves of the same file never share its temp file. */
	private static final Object WRITE_LOCK = new Object();

	private JsonFiles() {
	}

	public static JsonObject read(Path file) {
		if (!Files.isRegularFile(file)) return null;
		try {
			String content = Files.readString(file, StandardCharsets.UTF_8);
			JsonElement el = JsonParser.parseString(content);
			if (el.isJsonObject()) return el.getAsJsonObject();
			throw new IllegalStateException("top level is not an object");
		} catch (Exception e) {
			Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
			CoffeeClient.LOG.warn("Could not read {} ({}); using defaults. The broken file was moved to {}", file, e.getMessage(), backup.getFileName());
			try {
				Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
				// leave it; it will be overwritten on next save
			}
			return null;
		}
	}

	/** Write via a temp file and atomic rename so a crash mid-write never leaves a truncated config. */
	public static void writeAtomic(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		synchronized (WRITE_LOCK) {
			Files.writeString(tmp, content, StandardCharsets.UTF_8);
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}
}
