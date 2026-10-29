package dev.coffeeclient.util;

import dev.coffeeclient.core.CoffeeClient;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Opens a file or folder in the OS file manager, or a web page in the browser. The client spawns the platform's own opener
 * (xdg-open / open / explorer) rather than vanilla's {@code Util.getPlatform().openPath}, which 26.3 dropped.
 */
public final class Open {
	private Open() {
	}

	public static void path(Path path) {
		open(path.toAbsolutePath().toString());
	}

	/** Opens a web page in the default browser. */
	public static void url(String url) {
		if (url.startsWith("https://") || url.startsWith("http://")) open(url);
	}

	private static void open(String target) {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		List<String> command = os.contains("win") ? List.of("explorer", target)
			: os.contains("mac") ? List.of("open", target)
			: List.of("xdg-open", target);
		try {
			new ProcessBuilder(command).start();
		} catch (IOException e) {
			CoffeeClient.LOG.warn("Could not open {}", target, e);
		}
	}
}
