package dev.coffeeclient.module.modules.misc;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.ActionSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.util.Async;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import dev.coffeeclient.util.Open;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** After you take a screenshot: toast with the file name, copy its path, optionally open it. */
public final class ScreenshotTools extends Module {
	public final BooleanSetting copyPath = setting(new BooleanSetting("Copy path", "Copy the screenshot's file path to the clipboard", true));
	public final BooleanSetting toast = setting(new BooleanSetting("Toast", "Show the saved file name", true));
	public final BooleanSetting openAfter = setting(new BooleanSetting("Open after capture", "Open the image in your viewer", false));
	public final ActionSetting openFolder = setting(new ActionSetting("Open folder", "Open the screenshots folder", () -> Open.path(dir())));

	public ScreenshotTools() {
		super("ScreenshotTools", Category.MISC, "Extra options for screenshots.");
	}

	private static Path dir() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.KeyPress.class, e -> {
			if (e.action() != 1) return;
			if (!mc().options.keyScreenshot.matches(new KeyEvent(e.key(), e.scancode(), e.modifiers()))) return;
			long pressed = System.currentTimeMillis();
			// the game writes the file asynchronously; look for it shortly after
			Async.ioLater(() -> findNewest(pressed), 1500);
		});
	}

	private void findNewest(long since) {
		Path newest;
		try (Stream<Path> files = Files.list(dir())) {
			newest = files.filter(p -> p.toString().endsWith(".png"))
				.filter(p -> p.toFile().lastModified() >= since - 2000)
				.max(Comparator.comparingLong(p -> p.toFile().lastModified())).orElse(null);
		} catch (IOException e) {
			return;
		}
		if (newest == null) return;
		Path file = newest;
		Minecraft.getInstance().execute(() -> {
			if (copyPath.get()) mc().keyboardHandler.setClipboard(file.toAbsolutePath().toString());
			if (toast.get()) notify("Saved " + file.getFileName() + (copyPath.get() ? " (path copied)" : ""));
			if (openAfter.get()) Open.path(file);
		});
	}
}
