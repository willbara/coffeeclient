package dev.coffeeclient.util;

import dev.coffeeclient.core.CoffeeClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs per-frame / per-tick code defensively: the first failure for a given key is logged with its stack
 * trace, later failures for the same key are only counted. This keeps a broken draw call (or an
 * incompatibility with another mod) from crashing the game or flooding the log every frame.
 */
public final class ErrorGuard {
	private static final Map<String, AtomicInteger> FAILURES = new ConcurrentHashMap<>();

	private ErrorGuard() {
	}

	public static void run(String key, Runnable action) {
		try {
			action.run();
		} catch (Throwable t) {
			report(key, t);
		}
	}

	public static void report(String key, Throwable t) {
		AtomicInteger count = FAILURES.computeIfAbsent(key, k -> new AtomicInteger());
		if (count.getAndIncrement() == 0) {
			CoffeeClient.LOG.error("[{}] failed; further errors from here are suppressed", key, t);
		}
	}

	public static int failureCount() {
		return FAILURES.values().stream().mapToInt(AtomicInteger::get).sum();
	}
}
