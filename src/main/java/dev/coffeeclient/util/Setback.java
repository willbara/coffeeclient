package dev.coffeeclient.util;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.modules.client.Notifications;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Notices server setbacks (position corrections) and blames the movement modules that were active
 * just before: joins, respawns and dimension changes get a
 * grace period, and warnings have a cooldown. {@link #recent} lets modules react the way
 * Speed or ElytraFly reset themselves after rubber-banding.
 *
 * <p>Each correction is also matched against the last {@link #HISTORY} positions we sent: how many
 * of them it threw away ({@link #lastRevertTicks}) and how far its target is from the nearest one
 * ({@link #lastDeviation}).
 *
 * <p>Warnings and the optional auto-disable are switched in the Notifications module.
 */
public final class Setback {
	/** A module switched off this recently still counts as the likely cause. */
	private static final long ACTIVE_WINDOW_MS = 2000;
	private static final long GRACE_MS = 3000;
	private static final long WARN_COOLDOWN_MS = 2000;
	/** Corrections farther than this are real teleports (commands, pearls, portals), not setbacks. */
	private static final double MAX_DISTANCE = 64;
	static final int HISTORY = 100;

	private static volatile long graceUntil;
	private static volatile long last;
	private static long lastWarned;
	/** Positions we sent, newest last; guarded by itself (sends come from any thread). */
	private static final java.util.ArrayDeque<Vec3> sent = new java.util.ArrayDeque<>();
	private static volatile int revertTicks;
	private static volatile double deviation;

	private Setback() {
	}

	public static void init(EventBus bus) {
		// Network thread: only stamps the grace period.
		bus.subscribe(Events.PacketReceive.class, EventBus.LOWEST, e -> {
			if (!e.isCancelled() && (e.packet instanceof ClientboundLoginPacket || e.packet instanceof ClientboundRespawnPacket)) {
				graceUntil = System.currentTimeMillis() + GRACE_MS;
			}
		});
		bus.subscribe(Events.PacketOut.class, e -> {
			if (!(e.packet() instanceof ServerboundMovePlayerPacket move) || !move.hasPosition()) return;
			synchronized (sent) {
				sent.addLast(new Vec3(move.getX(0), move.getY(0), move.getZ(0)));
				if (sent.size() > HISTORY) sent.removeFirst();
			}
		});
		bus.subscribe(Events.ServerTeleport.class, e -> onTeleport(e.distance(), e.to()));
	}

	/** Sent positions the last setback took back (0 when it matched the newest one). */
	public static int lastRevertTicks() {
		return revertTicks;
	}

	/** Blocks between the last setback's target and the nearest position we sent. */
	public static double lastDeviation() {
		return deviation;
	}

	/** When the last setback happened (epoch ms, 0 = never). */
	public static long lastAt() {
		return last;
	}

	/** Index from the newest of the sent position nearest {@code to}, and its distance. */
	static double[] match(List<Vec3> sentOldestFirst, Vec3 to) {
		int best = -1;
		double bestD = Double.MAX_VALUE;
		for (int i = sentOldestFirst.size() - 1; i >= 0; i--) {
			double d = sentOldestFirst.get(i).distanceTo(to);
			if (d < bestD) {
				bestD = d;
				best = sentOldestFirst.size() - 1 - i;
			}
		}
		return best < 0 ? new double[]{0, 0} : new double[]{best, bestD};
	}

	/** True when the server set the player back within the last {@code ms} milliseconds. */
	public static boolean recent(long ms) {
		long at = last;
		return at > 0 && System.currentTimeMillis() - at < ms;
	}

	private static void onTeleport(double distance, Vec3 to) {
		long now = System.currentTimeMillis();
		if (now < graceUntil || distance > MAX_DISTANCE) return;
		List<Vec3> history;
		synchronized (sent) {
			history = new ArrayList<>(sent);
		}
		double[] hit = match(history, to);
		revertTicks = (int) hit[0];
		deviation = hit[1];
		last = now;

		List<Module> suspects = new ArrayList<>();
		for (Module m : CoffeeClient.modules().all()) {
			if (m.affectsMovement() && m.activeWithin(ACTIVE_WINDOW_MS)) suspects.add(m);
		}
		if (suspects.isEmpty()) return;

		Notifications settings = CoffeeClient.modules().get(Notifications.class);
		if (settings.setbackDisable.get()) {
			for (Module m : suspects) if (m.isEnabled()) m.setEnabled(false);
		}
		if (!settings.isEnabled() || !settings.setbackWarnings.get() || now - lastWarned < WARN_COOLDOWN_MS) return;
		lastWarned = now;
		StringBuilder names = new StringBuilder();
		for (int i = 0; i < suspects.size() && i < 3; i++) names.append(i == 0 ? "" : ", ").append(suspects.get(i).name());
		if (suspects.size() > 3) names.append(" +").append(suspects.size() - 3);
		CoffeeClient.LOG.info("[Setback] {} blocks, {} ticks back, active: {}", String.format(java.util.Locale.ROOT, "%.2f", distance), revertTicks, names);
		CoffeeClient.hud().toast("Setback detected (" + names + ", " + revertTicks + " ticks back)", CoffeeClient.theme().accent);
	}
}
