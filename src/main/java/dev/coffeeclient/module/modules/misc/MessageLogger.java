package dev.coffeeclient.module.modules.misc;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.ActionSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.StringSetting;
import dev.coffeeclient.util.Async;
import dev.coffeeclient.util.Open;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;

/** Saves chat to {@code config/coffeeclient/chatlogs/<date>.log} (written on the I/O thread). */
public final class MessageLogger extends Module {
	public final BooleanSetting includeSystem = setting(new BooleanSetting("System messages", "Also log server/system messages", true));
	public final BooleanSetting timestamps = setting(new BooleanSetting("Timestamps", "Prefix lines with the time", true));
	public final StringSetting filter = setting(new StringSetting("Only containing", "Comma-separated words; empty logs everything", "", 120));
	public final ActionSetting openFolder = setting(new ActionSetting("Open log folder", "Open the chat log folder", () -> {
		Path dir = root();
		try {
			Files.createDirectories(dir);
		} catch (IOException ignored) {
			// opening will just fail visibly
		}
		Open.path(dir);
	}));

	public MessageLogger() {
		super("MessageLogger", Category.MISC, "Saves chat history to a file.");
	}

	private static Path root() {
		return CoffeeClient.config().directory().resolve("chatlogs");
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.ChatReceived.class, e -> {
			if ((e.system() && !includeSystem.get())) return;
			String text = e.message().getString();
			String words = filter.get().trim().toLowerCase(Locale.ROOT);
			if (!words.isEmpty() && Arrays.stream(words.split(",")).map(String::trim).filter(w -> !w.isEmpty())
				.noneMatch(w -> text.toLowerCase(Locale.ROOT).contains(w))) return;
			String line = (timestamps.get() ? "[" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " : "") + text + System.lineSeparator();
			Path file = root().resolve(LocalDate.now() + ".log");
			Async.io(() -> {
				try {
					Files.createDirectories(file.getParent());
					Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				} catch (IOException ex) {
					CoffeeClient.LOG.debug("Chat log write failed: {}", ex.toString());
				}
			});
		});
	}
}
