package dev.coffeeclient.util;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Holds outgoing packets back and sends them later, in the order they were made. A module calls
 * {@link #hold} from its {@code PacketSend} handler and {@link #release} when it is done (or is turned
 * off). Released packets go through {@code PacketSend} again, but {@link #hold} ignores them, so they
 * can't be held twice. Held packets are dropped on a world change or on leaving: they describe a
 * place the player no longer is. May be called from the network thread.
 */
public final class OutboundHold {
	private record Held(Object owner, Packet<?> packet, long time) {
	}

	private static final List<Held> held = new ArrayList<>();
	private static volatile boolean sending;

	private OutboundHold() {
	}

	public static void init(EventBus bus) {
		bus.subscribe(Events.WorldChange.class, e -> clear());
		bus.subscribe(Events.GameLeft.class, e -> clear());
	}

	/** Cancels the packet and keeps it for {@code owner}; false (and nothing done) for a packet being released. */
	public static boolean hold(Object owner, Events.PacketSend e) {
		if (sending) return false;
		synchronized (held) {
			held.add(new Held(owner, e.packet, System.currentTimeMillis()));
		}
		e.cancel();
		return true;
	}

	/** Sends everything {@code owner} holds, oldest first, in one batch. */
	public static void release(Object owner) {
		releaseUntil(owner, Long.MAX_VALUE);
	}

	/** Sends what {@code owner} has held since {@code time} (wall clock) or earlier. */
	public static void releaseUntil(Object owner, long time) {
		List<Packet<?>> mine = new ArrayList<>();
		synchronized (held) {
			for (Iterator<Held> it = held.iterator(); it.hasNext(); ) {
				Held h = it.next();
				if (h.owner() != owner || h.time() > time) continue;
				mine.add(h.packet());
				it.remove();
			}
		}
		var connection = Minecraft.getInstance().getConnection();
		if (connection == null || mine.isEmpty()) return;
		sending = true;
		try {
			PacketBatch.run(() -> {
				for (Packet<?> p : mine) connection.send(p);
			});
		} finally {
			sending = false;
		}
	}

	public static int count(Object owner) {
		synchronized (held) {
			int n = 0;
			for (Held h : held) if (h.owner() == owner) n++;
			return n;
		}
	}

	/** When the oldest packet {@code owner} holds was made, or 0 when it holds none. */
	public static long oldest(Object owner) {
		synchronized (held) {
			for (Held h : held) if (h.owner() == owner) return h.time();
			return 0;
		}
	}

	private static void clear() {
		synchronized (held) {
			held.clear();
		}
	}
}
