package dev.coffeeclient.util;

import dev.coffeeclient.core.CoffeeClient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Background threads: one for all disk I/O, so the render thread never touches the filesystem, and one
 * for heavy combat maths ({@link #calc}).
 */
public final class Async {
	private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "Coffee Client IO");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY + 1);
		return t;
	});

	private static final ExecutorService CALC = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Coffee Client Calc");
		t.setDaemon(true);
		return t;
	});

	private Async() {
	}

	public static void io(Runnable task) {
		IO.execute(() -> ErrorGuard.run("io-task", task));
	}

	public static ScheduledFuture<?> ioLater(Runnable task, long delayMs) {
		return IO.schedule(() -> ErrorGuard.run("io-task", task), delayMs, TimeUnit.MILLISECONDS);
	}

	/** Runs {@code task} on the calc thread; the future holds null when it throws. */
	public static <T> CompletableFuture<T> calc(Supplier<T> task) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return task.get();
			} catch (RuntimeException e) {
				// World reads race the client thread; a failed pass just yields nothing this time. Logged
				// once, so a pass that always fails shows up instead of passing for "nothing found".
				ErrorGuard.report("calc-task", e);
				return null;
			}
		}, CALC);
	}

	/** Block until queued I/O has finished (shutdown only). */
	public static void flush(long timeoutMs) {
		try {
			IO.submit(() -> {
			}).get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			// best effort on shutdown, but say so: queued saves may not have been written
			CoffeeClient.LOG.warn("Queued saves did not finish within {} ms: {}", timeoutMs, e.toString());
		}
	}
}
