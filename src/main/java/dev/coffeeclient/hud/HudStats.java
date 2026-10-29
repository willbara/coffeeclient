package dev.coffeeclient.hud;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayDeque;

/**
 * Values for the speed and TPS HUD elements.
 *
 * <p>Speed: horizontal movement of the player (or what it rides) per tick, smoothed.
 * TPS: in singleplayer, read from the integrated server; on a server, estimated from how fast the
 * server's game time advances between its once-per-second time updates ({@link #onServerTime}).
 */
public final class HudStats {
	private static final int SAMPLES = 6;

	private static final ArrayDeque<long[]> timeSamples = new ArrayDeque<>();
	private static double speed;

	private HudStats() {
	}

	public static void init(EventBus bus) {
		bus.subscribe(Events.Tick.class, e -> tick(e.client()));
	}

	private static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null) {
			speed = 0;
			timeSamples.clear();
			lastUpdateMillis = 0;
			return;
		}
		Entity moving = player.getRootVehicle();
		double dx = moving.getX() - moving.xo, dz = moving.getZ() - moving.zo;
		double perSecond = Math.sqrt(dx * dx + dz * dz) * 20.0;
		speed += (perSecond - speed) * 0.35;
		if (speed < 0.005) speed = 0;
	}

	private static long lastUpdateMillis;

	/** Wall-clock time of the last server time update (0 = none yet). */
	public static long lastServerUpdate() {
		return lastUpdateMillis;
	}

	/** Called from the time-packet mixin on the client thread. */
	public static void onServerTime(long gameTime) {
		lastUpdateMillis = System.currentTimeMillis();
		long now = System.nanoTime();
		long[] last = timeSamples.peekLast();
		if (last != null && gameTime < last[0]) timeSamples.clear(); // time went backwards: new world/dimension
		timeSamples.addLast(new long[] {gameTime, now});
		while (timeSamples.size() > SAMPLES) timeSamples.removeFirst();
	}

	/** Horizontal speed in blocks per second. */
	public static double speed() {
		return speed;
	}

	/** Server ticks per second, or NaN when not yet known. */
	public static double tps() {
		Minecraft mc = Minecraft.getInstance();
		MinecraftServer server = mc.getSingleplayerServer();
		if (server != null) {
			double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
			float target = server.tickRateManager().tickrate();
			return mspt <= 0 ? target : Math.min(target, 1000.0 / mspt);
		}
		if (timeSamples.size() < 2) return Double.NaN;
		long[] first = timeSamples.peekFirst(), last = timeSamples.peekLast();
		double seconds = (last[1] - first[1]) / 1_000_000_000.0;
		if (seconds <= 0.5) return Double.NaN;
		return (last[0] - first[0]) / seconds;
	}

	public static void reset() {
		lastUpdateMillis = 0;
		timeSamples.clear();
	}
}
