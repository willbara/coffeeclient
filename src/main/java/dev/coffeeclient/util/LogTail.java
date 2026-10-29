package dev.coffeeclient.util;

import dev.coffeeclient.core.CoffeeClient;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tails the game log for the dashboard. A Log4j appender captures new lines live; lines written before
 * the mod loaded are back-filled from {@code logs/latest.log} on the I/O thread.
 */
public final class LogTail {
	public enum Level {
		INFO, WARN, ERROR
	}

	/**
	 * One log line. {@code continuation} marks stack-trace and wrapped lines that belong to the line
	 * before; {@code noise} marks known harmless chatter (and its continuations).
	 */
	public record Line(String text, Level level, boolean noise, boolean continuation) {
	}

	/**
	 * Messages the game, its libraries and servers print all the time without anything being wrong
	 * (unknown tab-list entries, offline auth, fishing hooks of unloaded players, ...).
	 */
	private static final List<String> NOISE = List.of(
		"Ignoring player info update for unknown player",
		"Failed to recreate fishing hook",
		"Received passengers for unknown entity",
		"Failed to fetch user properties",
		"Failed to retrieve profile key pair",
		"Could not authorize you against Realms",
		"Failed to fetch Realms feature flags",
		"Realms authentication error",
		"Missing sound for event",
		"Unknown custom packet identifier",
		"Ignoring unknown attribute",
		"Received unknown boss bar",
		"Ignoring entity data for unknown entity",
		"Failed to find model for");

	private static boolean noisy(String text) {
		for (String n : NOISE) if (text.contains(n)) return true;
		return false;
	}

	/** {@code [time] [thread/LEVEL] (logger) message} / {@code [time] [thread/LEVEL]: message} */
	private static final Pattern FILE_LINE = Pattern.compile("^\\[[^\\]]*] \\[[^/\\]]*/([A-Z]+)]:?\\s*(?:\\(([^)]*)\\)\\s*)?(.*)$");

	private static final Deque<Line> LINES = new ArrayDeque<>();
	private static int capacity = 5000;
	private static long total;
	private static long errors;
	private static long warnings;
	private static long noise;
	/** Whether the last whole line was noise, so its stack trace is too (appender thread / back-fill). */
	private static boolean lastNoise;
	private static int version;

	private LogTail() {
	}

	public static void install() {
		long backfillLimit = -1;
		Path latest = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
		try {
			if (Files.isRegularFile(latest)) backfillLimit = Files.size(latest);
		} catch (Exception ignored) {
			// no back-fill
		}
		try {
			if (LogManager.getContext(false) instanceof LoggerContext ctx) {
				Configuration config = ctx.getConfiguration();
				DashboardAppender appender = new DashboardAppender();
				appender.start();
				config.addAppender(appender);
				config.getRootLogger().addAppender(appender, null, null);
				ctx.updateLoggers();
			} else {
				CoffeeClient.LOG.warn("Log4j core not available; dashboard log will only show the log file snapshot");
			}
		} catch (Throwable t) {
			CoffeeClient.LOG.warn("Could not attach dashboard log appender", t);
		}
		long limit = backfillLimit;
		if (limit > 0) Async.io(() -> backfill(latest, limit));
	}

	private static void backfill(Path file, long limit) {
		List<Line> loaded = new ArrayList<>();
		long e = 0, w = 0, n = 0, quiet = 0;
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
			new BoundedInput(Files.newInputStream(file), limit), StandardCharsets.UTF_8))) {
			String raw;
			Level previous = Level.INFO;
			boolean previousNoise = false;
			while ((raw = reader.readLine()) != null) {
				Matcher m = FILE_LINE.matcher(raw);
				if (m.matches()) {
					Level level = parseLevel(m.group(1));
					String logger = m.group(2);
					String text = format(logger, m.group(3));
					boolean isNoise = noisy(text);
					loaded.add(new Line(text, level, isNoise, false));
					previous = level;
					previousNoise = isNoise;
					n++;
					if (isNoise) quiet++;
					if (level == Level.ERROR) e++;
					else if (level == Level.WARN) w++;
				} else if (!raw.isBlank()) {
					loaded.add(new Line("    " + raw.strip(), previous, previousNoise, true));
				}
			}
		} catch (Exception ex) {
			CoffeeClient.LOG.debug("Log back-fill failed: {}", ex.toString());
			return;
		}
		synchronized (LINES) {
			List<Line> live = new ArrayList<>(LINES);
			LINES.clear();
			int start = Math.max(0, loaded.size() + live.size() - capacity);
			for (int i = start; i < loaded.size(); i++) LINES.addLast(loaded.get(i));
			for (Line l : live) LINES.addLast(l);
			while (LINES.size() > capacity) LINES.removeFirst();
			total += n;
			errors += e;
			warnings += w;
			noise += quiet;
			version++;
		}
	}

	private static Level parseLevel(String s) {
		return switch (s) {
			case "ERROR", "FATAL" -> Level.ERROR;
			case "WARN" -> Level.WARN;
			default -> Level.INFO;
		};
	}

	private static String format(String logger, String message) {
		if (logger == null || logger.isEmpty()) return message;
		int dot = logger.lastIndexOf('.');
		return "[" + (dot >= 0 ? logger.substring(dot + 1) : logger) + "] " + message;
	}

	static void add(String text, Level level, boolean continuation) {
		synchronized (LINES) {
			boolean isNoise = continuation ? lastNoise : noisy(text);
			if (!continuation) lastNoise = isNoise;
			LINES.addLast(new Line(text, level, isNoise, continuation));
			while (LINES.size() > capacity) LINES.removeFirst();
			if (!continuation) {
				total++;
				if (isNoise) noise++;
				if (level == Level.ERROR) errors++;
				else if (level == Level.WARN) warnings++;
			}
			version++;
		}
	}

	public static void setCapacity(int lines) {
		synchronized (LINES) {
			capacity = Math.max(100, lines);
			while (LINES.size() > capacity) LINES.removeFirst();
			version++;
		}
	}

	/** Copy of the buffered lines (call sparingly; the dashboard re-snapshots only when {@link #version()} changes). */
	public static List<Line> snapshot() {
		synchronized (LINES) {
			return new ArrayList<>(LINES);
		}
	}

	public static int version() {
		synchronized (LINES) {
			return version;
		}
	}

	public static long total() {
		return total;
	}

	public static long errors() {
		return errors;
	}

	public static long warnings() {
		return warnings;
	}

	/** Lines recognised as harmless chatter (see {@link #NOISE}). */
	public static long noise() {
		return noise;
	}

	/** Reads at most {@code limit} bytes so back-fill stops where the live appender took over. */
	private static final class BoundedInput extends java.io.FilterInputStream {
		private long remaining;

		BoundedInput(java.io.InputStream in, long limit) {
			super(in);
			this.remaining = limit;
		}

		@Override
		public int read() throws java.io.IOException {
			if (remaining <= 0) return -1;
			int b = super.read();
			if (b >= 0) remaining--;
			return b;
		}

		@Override
		public int read(byte[] buf, int off, int len) throws java.io.IOException {
			if (remaining <= 0) return -1;
			int n = super.read(buf, off, (int) Math.min(len, remaining));
			if (n > 0) remaining -= n;
			return n;
		}
	}

	private static final class DashboardAppender extends AbstractAppender {
		DashboardAppender() {
			super("CoffeeClientDashboard", null, null, true, Property.EMPTY_ARRAY);
		}

		@Override
		public void append(LogEvent event) {
			org.apache.logging.log4j.Level l = event.getLevel();
			Level level = l.isMoreSpecificThan(org.apache.logging.log4j.Level.ERROR) ? Level.ERROR
				: l.isMoreSpecificThan(org.apache.logging.log4j.Level.WARN) ? Level.WARN : Level.INFO;
			String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
			int nl = message.indexOf('\n');
			if (nl >= 0) message = message.substring(0, nl);
			add(format(event.getLoggerName(), message), level, false);
			Throwable thrown = event.getThrown();
			if (thrown != null) add("    " + thrown, level, true);
		}
	}
}
