package dev.coffeeclient.core;

import dev.coffeeclient.util.ErrorGuard;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Minimal synchronous event bus. Events are plain objects (see {@link Events}); handlers are keyed by the
 * exact event class and run highest priority first (ties keep subscription order). A handler that throws
 * is logged once and keeps receiving events.
 *
 * <p>Hot call sites (per-entity or per-block hooks) should ask {@link #has} before building an event,
 * so a hook nobody listens to costs a map lookup and no allocation. Modules subscribe through
 * {@code Module.listen}, which attaches their handlers only while they are enabled.
 */
public final class EventBus {
	public static final int HIGHEST = 200;
	public static final int HIGH = 100;
	public static final int NORMAL = 0;
	public static final int LOW = -100;
	public static final int LOWEST = -200;

	private record Entry(int priority, Consumer<Object> handler, String guardKey) {
	}

	private final Map<Class<?>, List<Entry>> handlers = new ConcurrentHashMap<>();

	public <E> void subscribe(Class<E> type, Consumer<? super E> handler) {
		subscribe(type, NORMAL, handler);
	}

	public <E> void subscribe(Class<E> type, int priority, Consumer<? super E> handler) {
		subscribe(type, priority, handler, "event:" + type.getSimpleName() + ":" + handler.getClass().getName());
	}

	/** @param guardKey names the handler in error reports (each key is logged once) */
	@SuppressWarnings("unchecked")
	public <E> void subscribe(Class<E> type, int priority, Consumer<? super E> handler, String guardKey) {
		Entry entry = new Entry(priority, (Consumer<Object>) handler, guardKey);
		List<Entry> list = handlers.computeIfAbsent(type, t -> new CopyOnWriteArrayList<>());
		synchronized (list) {
			int i = 0;
			while (i < list.size() && list.get(i).priority >= priority) i++;
			list.add(i, entry);
		}
	}

	public void unsubscribe(Class<?> type, Consumer<?> handler) {
		List<Entry> list = handlers.get(type);
		if (list == null) return;
		synchronized (list) {
			list.removeIf(e -> e.handler == handler);
		}
	}

	/** True when at least one handler listens for exactly {@code type}. */
	public boolean has(Class<?> type) {
		List<Entry> list = handlers.get(type);
		return list != null && !list.isEmpty();
	}

	public <E> E post(E event) {
		List<Entry> list = handlers.get(event.getClass());
		if (list == null) return event;
		for (Entry entry : list) {
			try {
				entry.handler.accept(event);
			} catch (Throwable t) {
				ErrorGuard.report(entry.guardKey, t);
			}
		}
		return event;
	}
}
