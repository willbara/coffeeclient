package dev.coffeeclient.module.modules.misc;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.ActionSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.StringListSetting;
import dev.coffeeclient.setting.StringSetting;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.DisconnectedScreen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;

/** Sends chat messages on a timer. */
public final class Spam extends Module {
	public final StringListSetting messages = group(new StringListSetting("Messages", "Messages to send",
		List.of("Coffee on crack!"), 256), "General");
	public final BooleanSetting useFile = group(new BooleanSetting("Read from file", "Also send the lines of a text file (one message per line)", false), "File");
	public final StringSetting file = group(new StringSetting("File", "Path to the text file; a relative path starts in the game folder", "spam.txt", 256), "File");
	public final BooleanSetting fileOnly = group(new BooleanSetting("File only", "Ignore the Messages list while a file is in use", false), "File");
	public final ActionSetting reload = group(new ActionSetting("Reload file", "Read the file again now", this::loadFile), "File");
	public final NumberSetting delay = group(new NumberSetting("Delay", "Ticks between messages", 20, 0, 200, 1), "General");
	public final BooleanSetting random = group(new BooleanSetting("Randomise", "Pick a random message each time", false), "General");
	public final BooleanSetting disableOnLeave = group(new BooleanSetting("Disable on leave", "Disable when you leave the server", true), "General");
	public final BooleanSetting disableOnDisconnect = group(new BooleanSetting("Disable on disconnect", "Disable when you get disconnected", true), "General");
	public final BooleanSetting autoSplit = group(new BooleanSetting("Auto split", "Split long messages", false), "Split");
	public final NumberSetting splitLength = group(new NumberSetting("Split length", "Characters per split message", 256, 1, 256, 1), "Split");
	public final NumberSetting splitDelay = group(new NumberSetting("Split delay", "Ticks between split messages", 20, 0, 200, 1), "Split");

	private static final int MAX_FILE_LINES = 5000;

	private final Random rand = new Random();
	private final Queue<String> queue = new ArrayDeque<>();
	private int timer;
	private int splitTimer;
	private int index;
	private List<String> fileLines = List.of();
	private String loadedPath = "";

	public Spam() {
		super("Spam", Category.MISC, "Sends chat messages on a timer.");
		splitLength.visibleWhen(autoSplit::get);
		splitDelay.visibleWhen(autoSplit::get);
		file.visibleWhen(useFile::get);
		fileOnly.visibleWhen(useFile::get);
		reload.visibleWhen(useFile::get);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public StringListSetting embeddedList() {
		return messages;
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.GameLeft.class, event -> {
			if (disableOnLeave.get()) toggle();
		});
		listen(Events.OpenScreen.class, event -> {
			if (disableOnDisconnect.get() && event.screen instanceof DisconnectedScreen) toggle();
		});
	}

	/** (Re)reads the message file; a missing or unreadable file leaves no lines and says why. */
	private void loadFile() {
		loadedPath = file.get();
		fileLines = List.of();
		if (loadedPath.isBlank()) return;
		Path path = Path.of(loadedPath);
		if (!path.isAbsolute()) path = FabricLoader.getInstance().getGameDir().resolve(path);
		try (var lines = Files.lines(path)) {
			fileLines = lines.map(String::strip).filter(l -> !l.isEmpty()).limit(MAX_FILE_LINES).toList();
			info("Loaded " + fileLines.size() + " lines from " + path.getFileName());
		} catch (IOException | UncheckedIOException e) {
			info("Cannot read " + path + ": " + e.getMessage());
		}
	}

	/** Messages to cycle: the list, the file's lines, or both. */
	private List<String> pool() {
		if (!useFile.get()) return messages.get();
		if (!file.get().equals(loadedPath)) loadFile();
		if (fileOnly.get()) return fileLines;
		List<String> all = new ArrayList<>(messages.get());
		all.addAll(fileLines);
		return all;
	}

	@Override
	protected void onEnable() {
		if (useFile.get()) loadFile();
		timer = delay.get().intValue();
		splitTimer = 0;
		queue.clear();
	}

	@Override
	protected void onDisable() {
		queue.clear();
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().getConnection() == null) return;

		if (!queue.isEmpty()) {
			if (splitTimer > 0) {
				splitTimer--;
				return;
			}
			splitTimer = splitDelay.get().intValue();
			send(queue.poll());
			return;
		}

		if (timer > 0) {
			timer--;
			return;
		}
		timer = delay.get().intValue();

		List<String> list = pool();
		if (list.isEmpty()) return;

		if (index >= list.size()) index = 0;
		String message = random.get() ? list.get(rand.nextInt(list.size())) : list.get(index);
		if (!random.get()) index = (index + 1) % list.size();

		if (autoSplit.get() && message.length() > splitLength.get()) {
			int length = splitLength.get().intValue();
			for (int i = 0; i < message.length(); i += length) {
				queue.add(message.substring(i, Math.min(message.length(), i + length)));
			}
			return;
		}
		send(message);
	}

	private void send(String message) {
		if (mc().getConnection() != null) mc().getConnection().sendChat(message);
	}
}
