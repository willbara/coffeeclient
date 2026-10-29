package dev.coffeeclient.util;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.core.PacketApplyHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Holds incoming packets back and applies them later, in the order they arrived. A module calls
 * {@link #hold} from its {@code PacketApply.Pre} handler and {@link #release} when it is done (or is
 * turned off). Released packets go through {@code PacketApply} again, but {@link #hold} ignores them,
 * so they can't be held twice. Everything held is released on a world change and dropped on leaving.
 * Client thread only.
 */
public final class PacketHold {
	private record Held(Object owner, Packet<?> packet, long time) {
	}

	private static final List<Held> held = new ArrayList<>();
	private static boolean replaying;

	private PacketHold() {
	}

	public static void init(EventBus bus) {
		bus.subscribe(Events.WorldChange.class, e -> releaseAll());
		bus.subscribe(Events.GameLeft.class, e -> held.clear());
	}

	/** Cancels the packet and keeps it for {@code owner}; false (and nothing done) for a packet being released. */
	public static boolean hold(Object owner, Events.PacketApply.Pre e) {
		if (replaying) return false;
		held.add(new Held(owner, e.packet, System.currentTimeMillis()));
		e.cancel();
		return true;
	}

	/** Applies everything {@code owner} holds, oldest first. */
	public static void release(Object owner) {
		releaseUntil(owner, Long.MAX_VALUE);
	}

	/** Applies what {@code owner} has held since {@code time} (wall clock) or earlier, oldest first. */
	public static void releaseUntil(Object owner, long time) {
		List<Packet<?>> mine = new ArrayList<>();
		for (Iterator<Held> it = held.iterator(); it.hasNext(); ) {
			Held h = it.next();
			if (h.owner() != owner || h.time() > time) continue;
			mine.add(h.packet());
			it.remove();
		}
		apply(mine);
	}

	public static int count(Object owner) {
		int n = 0;
		for (Held h : held) if (h.owner() == owner) n++;
		return n;
	}

	private static void releaseAll() {
		List<Packet<?>> all = new ArrayList<>();
		for (Held h : held) all.add(h.packet());
		held.clear();
		apply(all);
	}

	@SuppressWarnings("unchecked")
	private static void apply(List<Packet<?>> packets) {
		var listener = Minecraft.getInstance().getConnection();
		if (listener == null || packets.isEmpty()) return;
		replaying = true;
		try {
			for (Packet<?> p : packets) {
				try {
					PacketApplyHooks.apply(p, () -> ((Packet<ClientGamePacketListener>) p).handle(listener));
				} catch (RuntimeException ex) {
					CoffeeClient.LOG.warn("[PacketHold] {} failed on release", p.type(), ex);
				}
			}
		} finally {
			replaying = false;
		}
	}
}
