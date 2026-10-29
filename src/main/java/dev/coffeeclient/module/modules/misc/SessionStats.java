package dev.coffeeclient.module.modules.misc;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.HudStats;
import dev.coffeeclient.hud.ModuleHudElement;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.ActionSetting;
import dev.coffeeclient.setting.BooleanSetting;

import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Locale;

/**
 * Session window: play time, distance, blocks broken, deaths, kills, XP gained, speed, world time and
 * packet rate for the current world, each with its own toggle, plus per-hour averages and peaks.
 */
public final class SessionStats extends Module {
	public final BooleanSetting showTime = setting(new BooleanSetting("Play time", "Time in this world", true));
	public final BooleanSetting showDistance = setting(new BooleanSetting("Distance", "Blocks travelled (teleports excluded)", true));
	public final BooleanSetting showBlocks = setting(new BooleanSetting("Blocks broken", "Blocks you mined", true));
	public final BooleanSetting showDeaths = setting(new BooleanSetting("Deaths", "Times you died", true));
	public final BooleanSetting showKills = setting(new BooleanSetting("Kills", "Mobs and players you hit that died soon after", false));
	public final BooleanSetting showXp = setting(new BooleanSetting("XP gained", "Experience points gained this session", false));
	public final BooleanSetting showSpeed = setting(new BooleanSetting("Speed", "Current speed in blocks per second", false));
	public final BooleanSetting showWorldTime = setting(new BooleanSetting("World time", "Time of day and day count in this world", false));
	public final BooleanSetting showPackets = setting(new BooleanSetting("Packet rate", "Packets per second, in and out", false));
	public final BooleanSetting showAverages = setting(new BooleanSetting("Averages", "Add per-hour rates, average speed and peaks next to the totals", false));
	public final BooleanSetting summary = setting(new BooleanSetting("Summary on leave", "Toast a summary when you disconnect", true));
	public final ActionSetting reset = setting(new ActionSetting("Reset", "Start a new session now", this::reset));

	private long start = System.currentTimeMillis();
	private double distance;
	private int blocks, deaths, kills;
	private int startXp = -1;
	private double topSpeed;
	/** Targets you hit, by entity id, with the time of the hit. */
	private final Map<Integer, Long> hits = new HashMap<>();
	/** Packets seen since the last tick (netty and client threads), and the last 20 ticks of each. */
	private final AtomicInteger inCount = new AtomicInteger(), outCount = new AtomicInteger();
	private final ArrayDeque<int[]> packetTicks = new ArrayDeque<>();
	private double lastX = Double.NaN, lastZ;
	private boolean wasDead, inWorld;

	public SessionStats() {
		super("SessionStats", Category.MISC, "Summarises play time, distance and blocks broken.");
	}

	private void reset() {
		start = System.currentTimeMillis();
		distance = 0;
		blocks = deaths = kills = 0;
		startXp = -1;
		topSpeed = 0;
		hits.clear();
		packetTicks.clear();
		inCount.set(0);
		outCount.set(0);
		lastX = Double.NaN;
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.BlockBroken.class, e -> blocks++);
		listen(Events.AttackEntity.class, e -> {
			if (e.target() instanceof LivingEntity) hits.put(e.target().getId(), System.currentTimeMillis());
		});
		listen(Events.EntityDeath.class, e -> {
			Long hit = hits.remove(e.entity().getId());
			if (hit != null && System.currentTimeMillis() - hit < 5000) kills++;
		});
		listen(Events.PacketReceive.class, e -> inCount.incrementAndGet());
		listen(Events.PacketSend.class, e -> outCount.incrementAndGet());
	}

	@Override
	public void onTick() {
		var p = mc().player;
		if (p == null) {
			if (inWorld) {
				if (summary.get()) notify(String.join(" · ", summaryParts()));
				reset();
			}
			inWorld = false;
			return;
		}
		if (!inWorld) reset();
		inWorld = true;
		if (!Double.isNaN(lastX)) {
			double d = Math.hypot(p.getX() - lastX, p.getZ() - lastZ);
			if (d < 10) distance += d;
		}
		lastX = p.getX();
		lastZ = p.getZ();
		if (startXp < 0) startXp = p.totalExperience;
		topSpeed = Math.max(topSpeed, HudStats.speed());
		packetTicks.addLast(new int[] {inCount.getAndSet(0), outCount.getAndSet(0)});
		while (packetTicks.size() > 20) packetTicks.removeFirst();
		long now = System.currentTimeMillis();
		hits.values().removeIf(t -> now - t > 5000);
		boolean dead = p.isDeadOrDying();
		if (dead && !wasDead) deaths++;
		wasDead = dead;
	}

	private List<String> summaryParts() {
		long s = (System.currentTimeMillis() - start) / 1000;
		List<String> parts = new ArrayList<>();
		parts.add(String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60));
		parts.add(distanceText());
		parts.add(blocks + " blocks");
		return parts;
	}

	/** " (1234/h)" once there is a minute of data, else nothing. */
	private static String perHour(int count, double hours) {
		return hours < 1.0 / 60 ? "" : String.format(Locale.ROOT, " (%.0f/h)", count / hours);
	}

	/** In-game clock and day count of the current world. */
	private String worldTime() {
		long dayTime = mc().level.getDefaultClockTime();
		long tick = Math.floorMod(dayTime, 24000L);
		int hour = (int) ((tick / 1000 + 6) % 24), minute = (int) (tick % 1000 * 60 / 1000);
		return String.format(Locale.ROOT, "Day %d %02d:%02d", dayTime / 24000 + 1, hour, minute);
	}

	private String distanceText() {
		return distance >= 1000 ? String.format(Locale.ROOT, "%.2f km", distance / 1000) : Math.round(distance) + " m";
	}

	@Override
	public List<HudElement> hudElements() {
		return List.of(new ModuleHudElement(this, "session", "Session stats", () -> {
			List<String[]> lines = new ArrayList<>();
			var p = mc().player;
			long s = (System.currentTimeMillis() - start) / 1000;
			double hours = Math.max(s, 1) / 3600.0;
			boolean avg = showAverages.get();
			if (showTime.get()) lines.add(ModuleHudElement.row("Time", String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)));
			if (showWorldTime.get() && mc().level != null) lines.add(ModuleHudElement.row("World time", worldTime()));
			if (showDistance.get()) lines.add(ModuleHudElement.row("Travelled", distanceText()));
			if (showSpeed.get()) {
				String now = String.format(Locale.ROOT, "%.1f b/s", HudStats.speed());
				lines.add(ModuleHudElement.row("Speed", avg
					? now + String.format(Locale.ROOT, " (avg %.1f, top %.1f)", s == 0 ? 0 : distance / s, topSpeed) : now));
			}
			if (showBlocks.get()) lines.add(ModuleHudElement.row("Mined", avg ? blocks + perHour(blocks, hours) : blocks));
			if (showKills.get()) lines.add(ModuleHudElement.row("Kills", avg ? kills + perHour(kills, hours) : kills));
			if (showDeaths.get()) lines.add(ModuleHudElement.row("Deaths", deaths));
			if (showXp.get() && p != null && startXp >= 0) {
				int gained = p.totalExperience - startXp;
				lines.add(ModuleHudElement.row("XP gained", avg ? gained + perHour(gained, hours) : gained));
			}
			if (showPackets.get()) {
				int in = 0, out = 0;
				for (int[] t : packetTicks) {
					in += t[0];
					out += t[1];
				}
				lines.add(ModuleHudElement.row("Packets/s", "\u2193" + in + " \u2191" + out));
			}
			return lines;
		}, (w, h) -> new int[] {w - 100, h / 2 - 30}));
	}
}
