package dev.coffeeclient.module;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Per-module time spent in ticks, world/HUD rendering and event handlers, in rolling one-second
 * windows.
 *
 * <p>Measuring is off unless a viewer (the dashboard panel) has called {@link #keepAlive()} within the
 * last couple of seconds, so normally the only cost is one volatile read per hook. Event handlers can
 * run on the network thread, so the counters are atomic.
 */
public final class ModuleProfiler {
	public enum Phase {
		TICK, RENDER, EVENT
	}

	private static final int PHASES = Phase.values().length;
	private static final long WINDOW_NS = 1_000_000_000L;
	private static final long LEASE_NS = 2_000_000_000L;

	/** Read on every hook: keep it a plain volatile flag. */
	private static volatile boolean active;
	private static volatile long leaseUntil;
	private static long windowStart = System.nanoTime();

	private ModuleProfiler() {
	}

	/** Counters owned by one module. */
	public static final class Stats {
		private final AtomicLongArray current = new AtomicLongArray(PHASES);
		/** Milliseconds per second in the last complete window, by phase. */
		private final double[] last = new double[PHASES];

		public double ms(Phase phase) {
			return last[phase.ordinal()];
		}

		public double totalMs() {
			double sum = 0;
			for (double v : last) sum += v;
			return sum;
		}
	}

	public static boolean active() {
		return active;
	}

	/** Keeps measuring on for a little while; call every frame while showing the numbers. */
	public static void keepAlive() {
		leaseUntil = System.nanoTime() + LEASE_NS;
		if (!active) {
			synchronized (ModuleProfiler.class) {
				windowStart = System.nanoTime();
			}
			active = true;
		}
	}

	public static void record(Module module, Phase phase, long nanos) {
		module.perf.current.addAndGet(phase.ordinal(), nanos);
	}

	/**
	 * Closes the window once a second has passed, and drops the lease when no viewer renewed it.
	 * Called from the client tick and by viewers, so a paused game still rolls over.
	 */
	public static void roll(List<Module> modules) {
		if (!active) return;
		long now = System.nanoTime();
		if (now - leaseUntil > 0) {
			active = false;
			for (Module m : modules) clear(m.perf);
			return;
		}
		synchronized (ModuleProfiler.class) {
			long elapsed = now - windowStart;
			if (elapsed < WINDOW_NS) return;
			windowStart = now;
			// Scale to "ms per second" so a late roll does not inflate the numbers.
			double scale = 1e-6 * WINDOW_NS / elapsed;
			for (Module m : modules) {
				for (int i = 0; i < PHASES; i++) m.perf.last[i] = m.perf.current.getAndSet(i, 0) * scale;
			}
		}
	}

	private static void clear(Stats stats) {
		for (int i = 0; i < PHASES; i++) {
			stats.current.set(i, 0);
			stats.last[i] = 0;
		}
	}

	/** Modules that used any time in the last window, most expensive first by {@code sortBy} (null = total). */
	public static List<Module> ranked(List<Module> modules, Phase sortBy) {
		List<Module> out = new ArrayList<>();
		for (Module m : modules) if (m.perf.totalMs() > 0) out.add(m);
		Comparator<Module> order = sortBy == null
			? Comparator.comparingDouble(m -> m.perf.totalMs())
			: Comparator.comparingDouble(m -> m.perf.ms(sortBy));
		out.sort(order.reversed().thenComparing(Module::name, String.CASE_INSENSITIVE_ORDER));
		return out;
	}
}
