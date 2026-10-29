package dev.coffeeclient.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups the packets sent inside {@link #run} into one network flush, so the server reads them in the
 * same batch (a slot swap, the action and the swap back; a Criticals hop and its attack).
 * {@code ConnectionMixin} writes without flushing while a batch is open
 * and flushes each touched connection when the outermost batch ends. Every packet sent from a
 * {@code PacketSend} handler joins the batch of the packet that triggered it.
 *
 * <p>Client thread only: sends from other threads flush as usual.
 */
public final class PacketBatch {
	private static int depth;
	private static final List<Connection> pending = new ArrayList<>(1);

	private PacketBatch() {
	}

	/** True while a batch is open on the client thread. */
	public static boolean active() {
		return depth > 0 && Minecraft.getInstance().isSameThread();
	}

	/** Runs {@code action}; packets it sends go out in one flush when the outermost batch ends. */
	public static void run(Runnable action) {
		if (!Minecraft.getInstance().isSameThread()) {
			action.run();
			return;
		}
		depth++;
		try {
			action.run();
		} finally {
			if (--depth == 0) flush();
		}
	}

	/** Called by the connection mixin for a write whose flush was held back. */
	public static void deferFlush(Connection connection) {
		if (!pending.contains(connection)) pending.add(connection);
	}

	private static void flush() {
		if (pending.isEmpty()) return;
		for (Connection connection : pending) connection.flushChannel();
		pending.clear();
	}
}
