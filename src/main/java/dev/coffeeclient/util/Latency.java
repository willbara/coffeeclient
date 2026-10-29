package dev.coffeeclient.util;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.hud.HudStats;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;

/**
 * How long the server takes to answer. Two smoothed samples
 * (TCP-style mean and deviation): the network ping from ping requests, and the action round trip from
 * the server acknowledging a block action (use, place, break), which also includes the wait for the
 * server's next tick. The tab list's ping is the fallback.
 *
 * <p>{@link #rtt} is how long until the server has seen and answered an action; {@link #timeout} is
 * how long to wait for an answer before assuming it was lost. Modules that need fresh numbers call
 * {@link #probe} every tick, which sends a ping request once a second.
 */
public final class Latency {
	private static final long PROBE_MS = 1000;
	private static final long SEQUENCE_LIFETIME = 5_000;
	/** Half a tick: on average an action waits this long for the server's next tick. */
	private static final int TICK_WAIT = 25;
	private static final int FALLBACK = 150;

	private static final Estimator network = new Estimator();
	private static final Estimator action = new Estimator();
	private static final Int2LongOpenHashMap sequences = new Int2LongOpenHashMap();
	private static long lastProbe;

	private Latency() {
	}

	public static void init(EventBus bus) {
		bus.subscribe(Events.PacketOut.class, e -> {
			int sequence = sequenceOf(e.packet());
			if (sequence <= 0) return;
			synchronized (sequences) {
				sequences.put(sequence, now());
			}
		});
		// Network thread: the pong is timed when it arrives, not when the client thread gets to it.
		bus.subscribe(Events.PacketReceive.class, EventBus.LOWEST, e -> {
			if (!e.isCancelled() && e.packet instanceof ClientboundPongResponsePacket pong) network.sample(now() - pong.time(), now());
		});
		bus.subscribe(Events.PacketApply.Pre.class, EventBus.HIGHEST, e -> {
			if (!(e.packet instanceof ClientboundBlockChangedAckPacket ack)) return;
			long now = now();
			synchronized (sequences) {
				if (sequences.containsKey(ack.sequence())) action.sample(now - sequences.get(ack.sequence()), now);
				sequences.int2LongEntrySet().removeIf(entry -> entry.getIntKey() <= ack.sequence() || now - entry.getLongValue() > SEQUENCE_LIFETIME);
			}
		});
		bus.subscribe(Events.GameLeft.class, e -> reset());
	}

	/** Keeps the network sample fresh: sends a ping request at most once a second. Call every tick while it matters. */
	public static void probe() {
		Minecraft mc = Minecraft.getInstance();
		long now = now();
		if (mc.getConnection() == null || now - lastProbe < PROBE_MS) return;
		lastProbe = now;
		mc.getConnection().send(new ServerboundPingRequestPacket(net.minecraft.util.Util.getMillis()));
	}

	/** One-way-and-back network ping in milliseconds. */
	public static int ping() {
		long now = now();
		if (network.fresh(now)) return (int) network.mean();
		int tab = tabLatency();
		if (tab > 0) return tab;
		if (action.fresh(now)) return (int) Math.max(action.mean() - TICK_WAIT, 0);
		return FALLBACK;
	}

	/** Milliseconds from sending an action until the server's answer is back. */
	public static int rtt() {
		long now = now();
		if (action.fresh(now)) return (int) action.mean();
		if (network.fresh(now)) return (int) network.mean() + TICK_WAIT;
		int tab = tabLatency();
		if (tab > 0) return tab + TICK_WAIT;
		return FALLBACK;
	}

	/** How much the round trip varies, in milliseconds. */
	public static int jitter() {
		return (int) (action.fresh(now()) ? action.deviation() : network.deviation());
	}

	/** How long to wait for an answer before assuming it was lost (150 ms to 3 s). */
	public static int timeout() {
		double deviation = action.fresh(now()) ? action.deviation() : network.deviation();
		return (int) Math.clamp(rtt() + Math.max(4 * deviation, 50) + 50, 150, 3000);
	}

	/** Length of one server tick in milliseconds (longer while the server lags). */
	public static long tickMs() {
		double tps = HudStats.tps();
		return tps > 0 ? (long) (1000 / Math.min(tps, 20)) : 50;
	}

	private static int sequenceOf(Packet<?> packet) {
		return switch (packet) {
			case ServerboundUseItemOnPacket p -> p.getSequence();
			case ServerboundUseItemPacket p -> p.getSequence();
			case ServerboundPlayerActionPacket p -> p.getSequence();
			default -> 0;
		};
	}

	private static int tabLatency() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.getConnection() == null) return 0;
		PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
		return info == null ? 0 : info.getLatency();
	}

	private static void reset() {
		network.reset();
		action.reset();
		lastProbe = 0;
		synchronized (sequences) {
			sequences.clear();
		}
	}

	private static long now() {
		return System.currentTimeMillis();
	}

	/** Smoothed mean and deviation of round-trip samples (the TCP retransmit-timer formula). */
	public static final class Estimator {
		/** A sample older than this no longer counts as fresh. */
		static final long SAMPLE_LIFETIME = 10_000;

		private double mean = -1;
		private double deviation;
		private long last;

		public synchronized void sample(long value, long now) {
			double rtt = Math.clamp(value, 0, 10_000);
			if (mean < 0) {
				mean = rtt;
				deviation = rtt / 2;
			} else {
				deviation = 0.75 * deviation + 0.25 * Math.abs(mean - rtt);
				mean = 0.875 * mean + 0.125 * rtt;
			}
			last = now;
		}

		public synchronized boolean fresh(long now) {
			return mean >= 0 && now - last < SAMPLE_LIFETIME;
		}

		public synchronized double mean() {
			return Math.max(mean, 0);
		}

		public synchronized double deviation() {
			return deviation;
		}

		public synchronized void reset() {
			mean = -1;
			deviation = 0;
			last = 0;
		}
	}
}
