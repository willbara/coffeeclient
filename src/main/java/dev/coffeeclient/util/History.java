package dev.coffeeclient.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Client notifications since startup: module chat lines, toasts and Notifier reports. In memory only, so
 * it is gone when the game quits, and capped so a long session cannot grow without bound.
 */
public final class History {
	/** {@code source} is the module (or Notifier) that sent the line. */
	public record Entry(long at, String source, String text) {
	}

	private static final int CAPACITY = 2000;
	private static final Deque<Entry> ENTRIES = new ArrayDeque<>();
	private static int version;

	private History() {
	}

	public static void add(String source, String text) {
		ENTRIES.addLast(new Entry(System.currentTimeMillis(), source, text));
		if (ENTRIES.size() > CAPACITY) ENTRIES.removeFirst();
		version++;
	}

	/** Copy of every entry, newest first. */
	public static List<Entry> newestFirst() {
		List<Entry> list = new ArrayList<>(ENTRIES);
		Collections.reverse(list);
		return list;
	}

	public static int version() {
		return version;
	}
}
