package dev.coffeeclient.util;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the last few seconds of packets and player state while some module wants it
 * ({@link #acquire}), and writes them to {@code kicks/} when you are disconnected: the reason,
 * a short summary (keep-alive gaps, setbacks) and then everything in time order. PacketInspector's
 * kick forensics and AutoReconnect's kick report both record through here, so one disconnect makes
 * one file.
 */
public final class FlightRecorder {
	private record Line(long at, String text) {
	}

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

	/** Who wants recording, and how many seconds each wants kept. */
	private static final Map<Object, Integer> owners = new ConcurrentHashMap<>();
	/** Guarded by {@code FlightRecorder.class}; packets come from both threads. */
	private static final ArrayDeque<Line> packets = new ArrayDeque<>();
	private static final ArrayDeque<Line> states = new ArrayDeque<>();
	private static final ArrayDeque<Long> keepAlives = new ArrayDeque<>();
	private static volatile String kickReason;
	private static int setbacks;

	private FlightRecorder() {
	}

	public static void init(EventBus bus) {
		bus.subscribe(Events.PacketReceive.class, EventBus.LOWEST, e -> {
			if (e.isCancelled() || owners.isEmpty()) return;
			if (e.packet instanceof ClientboundDisconnectPacket d) kickReason = d.reason().getString();
			if (e.packet instanceof ClientboundKeepAlivePacket) {
				synchronized (FlightRecorder.class) {
					keepAlives.addLast(System.currentTimeMillis());
				}
			}
			packet("IN ", e.packet);
		});
		bus.subscribe(Events.PacketOut.class, e -> {
			if (!owners.isEmpty()) packet("OUT", e.packet());
		});
		bus.subscribe(Events.Tick.class, e -> {
			if (!owners.isEmpty()) state();
		});
		bus.subscribe(Events.ServerTeleport.class, EventBus.LOWEST, e -> {
			if (owners.isEmpty()) return;
			synchronized (FlightRecorder.class) {
				setbacks++;
				states.addLast(new Line(System.currentTimeMillis(), String.format(Locale.ROOT, "SETBACK %.2f blocks, %d ticks back", e.distance(), Setback.lastRevertTicks())));
			}
		});
		bus.subscribe(Events.OpenScreen.class, EventBus.LOWEST, e -> {
			if (e.screen instanceof DisconnectedScreen && !owners.isEmpty()) dump();
		});
	}

	/** Starts (or keeps) recording on behalf of {@code owner}, keeping {@code seconds} of history. */
	public static void acquire(Object owner, int seconds) {
		owners.put(owner, seconds);
	}

	public static void release(Object owner) {
		if (owners.remove(owner) != null && owners.isEmpty()) clear();
	}

	private static long windowMs() {
		int s = 0;
		for (int v : owners.values()) s = Math.max(s, v);
		return s * 1000L;
	}

	private static void packet(String dir, Packet<?> packet) {
		String text = packet.toString();
		Line line = new Line(System.currentTimeMillis(), dir + " " + PacketIds.of(packet) + " " + (text.length() > 160 ? text.substring(0, 160) + "..." : text));
		synchronized (FlightRecorder.class) {
			packets.addLast(line);
			prune(line.at());
		}
	}

	private static void state() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) return;
		var info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(player.getUUID());
		Vec3 pos = player.position(), vel = player.getDeltaMovement();
		String text = String.format(Locale.ROOT, "pos %.3f %.3f %.3f  vel %.3f %.3f %.3f  ground %s  fall %.2f  ping %s",
			pos.x, pos.y, pos.z, vel.x, vel.y, vel.z, player.onGround(), player.fallDistance, info == null ? "?" : info.getLatency());
		long now = System.currentTimeMillis();
		synchronized (FlightRecorder.class) {
			states.addLast(new Line(now, text));
			prune(now);
		}
	}

	private static void prune(long now) {
		long window = windowMs();
		while (!packets.isEmpty() && now - packets.peekFirst().at() > window) packets.removeFirst();
		while (!states.isEmpty() && now - states.peekFirst().at() > window) {
			if (states.removeFirst().text().startsWith("SETBACK")) setbacks--;
		}
		while (!keepAlives.isEmpty() && now - keepAlives.peekFirst() > window) keepAlives.removeFirst();
	}

	private static synchronized void clear() {
		packets.clear();
		states.clear();
		keepAlives.clear();
		setbacks = 0;
		kickReason = null;
	}

	/** Writes what was kept, oldest first, to a new file in {@code kicks/}. */
	private static void dump() {
		List<Line> p, s;
		List<Long> k;
		int sb;
		synchronized (FlightRecorder.class) {
			if (packets.isEmpty() && states.isEmpty()) return;
			p = new ArrayList<>(packets);
			s = new ArrayList<>(states);
			k = new ArrayList<>(keepAlives);
			sb = setbacks;
		}
		String reason = kickReason;
		clear();
		long now = System.currentTimeMillis();
		StringBuilder out = new StringBuilder("Coffee kick report, ").append(STAMP.format(Instant.ofEpochMilli(now))).append('\n');
		out.append("Reason: ").append(reason == null ? "(no disconnect packet: connection lost or closed)" : reason).append('\n');
		out.append(summary(k, now, sb)).append("\n\n");
		out.append("== Player state, one line per tick ==\n");
		for (Line l : s) out.append(STAMP.format(Instant.ofEpochMilli(l.at()))).append("  ").append(l.text()).append('\n');
		out.append("\n== Packets ==\n");
		for (Line l : p) out.append(STAMP.format(Instant.ofEpochMilli(l.at()))).append("  ").append(l.text()).append('\n');
		Path file = CoffeeClient.config().directory().resolve("kicks").resolve("kick-" + FILE_STAMP.format(Instant.ofEpochMilli(now)) + ".log");
		Async.io(() -> {
			try {
				Files.createDirectories(file.getParent());
				Files.writeString(file, out);
				CoffeeClient.LOG.info("[FlightRecorder] Kick report saved to {}", file);
			} catch (IOException ex) {
				CoffeeClient.LOG.warn("[FlightRecorder] Could not save kick report: {}", ex.getMessage());
			}
		});
		CoffeeClient.hud().toast("Kick report saved: " + file.getFileName(), CoffeeClient.theme().accent);
	}

	/** Keep-alive cadence and setbacks in the window, as one line. */
	static String summary(List<Long> keepAlives, long now, int setbacks) {
		StringBuilder b = new StringBuilder("Keep-alives: ").append(keepAlives.size());
		if (keepAlives.size() > 1) {
			long longest = 0;
			for (int i = 1; i < keepAlives.size(); i++) longest = Math.max(longest, keepAlives.get(i) - keepAlives.get(i - 1));
			b.append(String.format(Locale.ROOT, ", average gap %.1f s, longest %.1f s",
				(keepAlives.getLast() - keepAlives.getFirst()) / 1000.0 / (keepAlives.size() - 1), longest / 1000.0));
		}
		if (!keepAlives.isEmpty()) b.append(String.format(Locale.ROOT, ", last %.1f s before the end", (now - keepAlives.getLast()) / 1000.0));
		return b.append(". Setbacks: ").append(setbacks).append('.').toString();
	}
}
