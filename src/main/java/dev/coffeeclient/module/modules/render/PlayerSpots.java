package dev.coffeeclient.module.modules.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.modules.misc.Friends;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.Alarm;
import dev.coffeeclient.util.AlertOutput;
import dev.coffeeclient.util.Jingle;
import dev.coffeeclient.util.Async;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.Coords;
import dev.coffeeclient.util.Nametag;
import dev.coffeeclient.util.Players;
import dev.coffeeclient.util.WorldProjector;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks other players around you: who logs out nearby, who comes into or leaves your render distance.
 * Each event can leave a marker in the world, alert you, and be written to a per-server log file.
 */
public final class PlayerSpots extends Module {
	private enum Kind {
		LOGOUT("logged out"), ENTER("entered render distance"), LEAVE("left render distance");

		final String verb;

		Kind(String verb) {
			this.verb = verb;
		}
	}

	private static final int GREEN = 0xFF19E119;
	private static final int ORANGE = 0xFFE16919;
	private static final int RED = 0xFFE11919;
	/** Logout and entity-removal packets can land a tick or two apart; wait this long before calling it "left range". */
	private static final int CLASSIFY_TICKS = 20;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	public final BooleanSetting logouts = group(new BooleanSetting("Logouts", "A loaded player disconnects", true), "Events");
	public final BooleanSetting entering = group(new BooleanSetting("Entering", "A player comes into your render distance", false), "Events");
	public final BooleanSetting leaving = group(new BooleanSetting("Leaving", "A player goes out of your render distance (still online)", false), "Events");
	public final BooleanSetting ignoreFriends = group(new BooleanSetting("Ignore friends", "Skip players on your friends list", false), "Events");
	public final BooleanSetting tabListOnly = group(new BooleanSetting("Tab list only", "Skip player entities missing from the tab list (NPCs, bots, fake players)", true), "Events");

	public final BooleanSetting markLogouts = group(new BooleanSetting("Mark logouts", "Leave a box where a player logged out", true), "Markers");
	public final BooleanSetting markEntering = group(new BooleanSetting("Mark entering", "Leave a box where a player first appeared", false), "Markers");
	public final BooleanSetting markLeaving = group(new BooleanSetting("Mark leaving", "Leave a box where a player was last seen", true), "Markers");
	public final NumberSetting lifetime = group(new NumberSetting("Marker lifetime", "Seconds a marker stays (0 = until the player returns)", 0, 0, 3600, 30), "Markers");
	public final NumberSetting maxDistance = group(new NumberSetting("Max distance", "Hide markers farther than this", 512, 16, 4096, 16), "Markers");

	public final BooleanSetting chatAlerts = group(new BooleanSetting("Chat alerts", "Post a chat line for each event", true), "Alerts");
	public final BooleanSetting toastAlerts = group(new BooleanSetting("Toast alerts", "Show a toast for each event", false), "Alerts");
	public final BooleanSetting logToFile = group(new BooleanSetting("Log to file", "Append events to config/coffeeclient/playerspots/<server>.log", false), "Alerts");

	public final BooleanSetting canary = group(new BooleanSetting("Canary", "Sound an alarm once for each player who comes into range, until they leave", false), "Canary");
	public final BooleanSetting canarySound = group(new BooleanSetting("Sound", "Play a warning sound", true), "Canary");
	public final IdListSetting canaryAlarmSound = group(Alarm.soundSetting(), "Canary");
	public final BooleanSetting canaryJingle = group(Alarm.useJingleSetting(), "Canary");
	public final EnumSetting<Jingle> canaryJingleTune = group(Alarm.jingleSetting(), "Canary");
	public final NumberSetting canarySoundCount = group(new NumberSetting("Sound count", "How many times the sound plays, half a second apart", 1, 1, 10, 1), "Canary");
	public final NumberSetting canaryVolume = group(new NumberSetting("Volume", "How loud each copy is (above 100% is boosted)", 0.5, 0.05, 2, 0.05).format(v -> Math.round(v * 100) + "%"), "Canary");
	public final NumberSetting canaryRepeat = group(new NumberSetting("Repeat every (s)", "Remind again while the player stays in range (0 = once)", 0, 0, 50, 1), "Canary");
	public final EnumSetting<AlertOutput> canaryOutput = group(new EnumSetting<>("Output", "Where to show the alert", AlertOutput.TOAST), "Canary");

	public final BooleanSetting fullHeight = group(new BooleanSetting("Full height", "Draw the player's full height (off = a flat mark)", true), "Render");
	public final EnumSetting<WorldOverlays.Shape> shape = group(new EnumSetting<>("Shape", "How each box is drawn", WorldOverlays.Shape.Both), "Render");
	public final ColorSetting logoutColor = group(new ColorSetting("Logout color", "Logout marker colour", 0xFFFF00FF), "Render");
	public final ColorSetting enterColor = group(new ColorSetting("Entering color", "Entering marker colour", 0xFF19E1E1), "Render");
	public final ColorSetting leaveColor = group(new ColorSetting("Leaving color", "Leaving marker colour", 0xFFE1C819), "Render");
	public final NumberSetting fillOpacity = group(new NumberSetting("Fill opacity", "Alpha of filled faces", 55, 0, 255, 5), "Render");
	public final BooleanSetting seeThrough = group(new BooleanSetting("See through walls", "Render through terrain", true), "Render");
	public final NumberSetting width = group(new NumberSetting("Outline width", "Outline thickness", 1.0, 0.5, 5, 0.5), "Render");

	public final BooleanSetting nametag = group(new BooleanSetting("Show nametag", "Draw a label above each marker", true), "Nametag");
	public final NumberSetting scale = group(new NumberSetting("Scale", "Label size", 1.0, 0.5, 2.5, 0.05), "Nametag");
	public final BooleanSetting showName = group(new BooleanSetting("Name", "The player's name", true), "Nametag");
	public final BooleanSetting showHealth = group(new BooleanSetting("Health", "Health when last seen, coloured by how much is left", true), "Nametag");
	public final BooleanSetting showEvent = group(new BooleanSetting("Event", "What happened: logout, entered or left", true), "Nametag");
	public final BooleanSetting showDistance = group(new BooleanSetting("Distance", "How far the marker is from you", false), "Nametag");
	public final BooleanSetting showAge = group(new BooleanSetting("Time since", "How long ago it happened", false), "Nametag");
	public final BooleanSetting showCoords = group(new BooleanSetting("Coordinates", "Where it happened", false), "Nametag");
	public final BooleanSetting showGear = group(new BooleanSetting("Last-seen gear", "The hands and armour the player had when last in range", true), "Nametag");
	public final BooleanSetting background = group(new BooleanSetting("Background", "Draw a panel behind the label", true), "Nametag");
	public final ColorSetting nameBackground = group(new ColorSetting("Name background", "Label panel colour (alpha sets its opacity)", 0xC0000000), "Nametag");
	public final ColorSetting nameColor = group(new ColorSetting("Name color", "Name colour", 0xFFFFFFFF), "Nametag");
	public final ColorSetting infoColor = group(new ColorSetting("Info color", "Colour of distance, time and coordinates", 0xFFAAAAAA), "Nametag");

	/** Last known state of a loaded player. */
	private record Seen(UUID uuid, String name, double x, double y, double z, double width, double height, int health, int maxHealth, List<ItemStack> gear) {
		static Seen of(Player p) {
			List<ItemStack> gear = new ArrayList<>(6);
			for (EquipmentSlot slot : GEAR) gear.add(p.getItemBySlot(slot));
			return new Seen(p.getUUID(), p.getName().getString(), p.getX(), p.getY(), p.getZ(), p.getBbWidth(), p.getBbHeight(),
				Math.round(p.getHealth() + p.getAbsorptionAmount()), Math.round(p.getMaxHealth() + p.getAbsorptionAmount()), gear);
		}
	}

	/** Gear order on the label, as Nametags draws it. */
	private static final EquipmentSlot[] GEAR = {EquipmentSlot.MAINHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND};

	private record Spot(Seen seen, Kind kind, long createdAt) {
	}

	private final Map<UUID, Seen> loaded = new HashMap<>();
	/** Players that just unloaded, waiting to see whether they also left the tab list (logout) or not (left range). */
	private final Map<UUID, Seen> pending = new HashMap<>();
	private final Map<UUID, Integer> pendingTicks = new HashMap<>();
	private final List<Spot> spots = new ArrayList<>();
	/** Players in range that already set off the canary, with when. */
	private final Map<UUID, Long> alarmed = new HashMap<>();
	private final Alarm alarm = new Alarm(canaryAlarmSound, canaryJingle, canaryJingleTune, canarySoundCount, canaryVolume);
	private ResourceKey<Level> dimension;
	/** False until the first scan after enabling or changing world, so players already around do not count as "entering". */
	private boolean primed;

	public PlayerSpots() {
		super("PlayerSpots", List.of("LogoutSpots", "Logout Spots", "Player Spots"), Category.RENDER,
			"Marks and logs players logging out, entering or leaving your render distance.");
		width.visibleWhen(() -> shape.get() != WorldOverlays.Shape.Filled);
		fillOpacity.visibleWhen(() -> shape.get() != WorldOverlays.Shape.Outline);
		for (Setting<?> s : List.of(canarySound, canaryRepeat, canaryOutput)) {
			s.visibleWhen(canary::get);
		}
		canaryJingle.visibleWhen(() -> canary.get() && canarySound.get());
		canaryJingleTune.visibleWhen(() -> canary.get() && canarySound.get() && canaryJingle.get());
		canaryAlarmSound.visibleWhen(() -> canary.get() && canarySound.get() && !canaryJingle.get());
		canarySoundCount.visibleWhen(() -> canary.get() && canarySound.get());
		canaryVolume.visibleWhen(() -> canary.get() && canarySound.get());
		for (Setting<?> s : List.of(scale, showName, showHealth, showEvent, showDistance, showAge, showCoords, background, nameColor, infoColor)) {
			s.visibleWhen(nametag::get);
		}
		nameBackground.visibleWhen(() -> nametag.get() && background.get());
		nameColor.visibleWhen(() -> nametag.get() && showName.get());
		infoColor.visibleWhen(() -> nametag.get() && (showDistance.get() || showAge.get() || showCoords.get()));
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return canary.get() && canarySound.get() && !canaryJingle.get() ? canaryAlarmSound : null;
	}

	@Override
	protected void onEnable() {
		reset();
	}

	@Override
	protected void onDisable() {
		reset();
	}

	private void reset() {
		loaded.clear();
		pending.clear();
		pendingTicks.clear();
		spots.clear();
		alarmed.clear();
		alarm.reset();
		dimension = null;
		primed = false;
	}

	@Override
	public void onTick() {
		Minecraft mc = mc();
		if (mc.level == null || mc.player == null || mc.getConnection() == null) return;

		if (mc.level.dimension() != dimension) {
			reset();
			dimension = mc.level.dimension();
		}

		alarm.tick();
		Map<UUID, Seen> now = new HashMap<>();
		for (Entity entity : mc.level.entitiesForRendering()) {
			// Negative ids are client-only fake players.
			if (!(entity instanceof Player player) || player == mc.player || player.getId() < 0) continue;
			if (tabListOnly.get() && !Players.isListed(player.getUUID())) continue;
			now.put(player.getUUID(), Seen.of(player));
		}

		for (Seen seen : now.values()) {
			if (pending.remove(seen.uuid()) != null) {
				pendingTicks.remove(seen.uuid());
				continue;
			}
			if (primed && !loaded.containsKey(seen.uuid())) event(Kind.ENTER, seen);
		}
		for (Seen seen : loaded.values()) {
			if (!now.containsKey(seen.uuid())) {
				pending.put(seen.uuid(), seen);
				pendingTicks.put(seen.uuid(), CLASSIFY_TICKS);
			}
		}
		loaded.clear();
		loaded.putAll(now);
		primed = true;
		if (canary.get()) canary(now.values());

		for (Iterator<Map.Entry<UUID, Seen>> it = pending.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Seen> entry = it.next();
			UUID uuid = entry.getKey();
			if (mc.getConnection().getPlayerInfo(uuid) == null) {
				event(Kind.LOGOUT, entry.getValue());
			} else {
				int left = pendingTicks.merge(uuid, -1, Integer::sum);
				if (left > 0) continue;
				event(Kind.LEAVE, entry.getValue());
			}
			pendingTicks.remove(uuid);
			it.remove();
		}

		// Seeing a player again clears their markers; old markers expire.
		long cutoff = lifetime.intValue() > 0 ? System.currentTimeMillis() - lifetime.intValue() * 1000L : Long.MIN_VALUE;
		spots.removeIf(spot -> now.containsKey(spot.seen().uuid()) && spot.kind() != Kind.ENTER || spot.createdAt() < cutoff);
	}

	private boolean skipFriend(Seen seen) {
		return ignoreFriends.get() && Friends.is(seen.name(), dev.coffeeclient.friend.FriendTrait.TRUSTED);
	}

	/** True once per player until they leave, or again after "Repeat every" seconds. */
	public boolean canaryDue(UUID uuid, long time) {
		Long last = alarmed.get(uuid);
		if (last != null && (canaryRepeat.get() <= 0 || time - last <= canaryRepeat.get() * 1000)) return false;
		alarmed.put(uuid, time);
		return true;
	}

	/** Forgets a player so the next sighting alarms again. */
	public void canaryForget(UUID uuid) {
		alarmed.remove(uuid);
	}

	private void canary(Iterable<Seen> inRange) {
		long time = System.currentTimeMillis();
		for (Seen seen : inRange) {
			if (skipFriend(seen) || !canaryDue(seen.uuid(), time)) continue;
			String text = seen.name() + " is nearby";
			if (canaryOutput.get() != AlertOutput.CHAT) notify(text);
			if (canaryOutput.get() != AlertOutput.TOAST) info(text);
			if (canarySound.get()) alarm.trigger();
		}
	}

	private void event(Kind kind, Seen seen) {
		if (kind != Kind.ENTER) canaryForget(seen.uuid());
		boolean wanted = switch (kind) {
			case LOGOUT -> logouts.get();
			case ENTER -> entering.get();
			case LEAVE -> leaving.get();
		};
		if (!wanted || skipFriend(seen)) return;

		boolean mark = switch (kind) {
			case LOGOUT -> markLogouts.get();
			case ENTER -> markEntering.get();
			case LEAVE -> markLeaving.get();
		};
		if (mark) {
			spots.removeIf(spot -> spot.seen().uuid().equals(seen.uuid()));
			spots.add(new Spot(seen, kind, System.currentTimeMillis()));
		}

		String where = Coords.xyz(seen.x(), seen.y(), seen.z());
		if (chatAlerts.get()) info(seen.name() + " " + kind.verb + " at " + where);
		if (toastAlerts.get()) notify(seen.name() + " " + kind.verb);
		if (logToFile.get()) log(kind, seen, where);
	}

	private void log(Kind kind, Seen seen, String where) {
		Minecraft mc = mc();
		var server = mc.getCurrentServer();
		String serverId = (server == null ? "singleplayer" : server.ip).replaceAll("[^a-zA-Z0-9._\\-]", "_");
		String dim = mc.level == null ? "?" : mc.level.dimension().identifier().toString();
		String line = "[" + LocalDateTime.now().format(TIME) + "] " + kind.name() + " " + seen.name()
			+ " " + dim + " " + where + " hp=" + seen.health() + System.lineSeparator();
		Path file = CoffeeClient.config().directory().resolve("playerspots").resolve(serverId + ".log");
		Async.io(() -> {
			try {
				Files.createDirectories(file.getParent());
				Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (Exception e) {
				CoffeeClient.LOG.warn("PlayerSpots could not write {}: {}", file, e.toString());
			}
		});
	}

	private int color(Kind kind) {
		return switch (kind) {
			case LOGOUT -> logoutColor.argb();
			case ENTER -> enterColor.argb();
			case LEAVE -> leaveColor.argb();
		};
	}

	@Override
	public void renderWorld(PoseStack poseStack, Vec3 cameraPos) {
		if (spots.isEmpty()) return;
		float h = fullHeight.get() ? 1f : 0.02f;
		double max2 = maxDistance.get() * maxDistance.get();
		for (Spot spot : spots) {
			Seen s = spot.seen();
			if (cameraPos.distanceToSqr(s.x(), s.y(), s.z()) > max2) continue;
			int argb = 0xFF000000 | (color(spot.kind()) & 0xFFFFFF);
			poseStack.pushPose();
			poseStack.translate(s.x() - s.width() / 2.0 - cameraPos.x, s.y() - cameraPos.y, s.z() - s.width() / 2.0 - cameraPos.z);
			poseStack.scale((float) s.width(), h * (float) s.height(), (float) s.width());
			WorldOverlays.cube(poseStack, shape.get(), seeThrough.get(), argb, fillOpacity.intValue(), width.get().floatValue());
			poseStack.popPose();
		}
	}

	@Override
	public void renderOverlay(Ui ui, WorldProjector projector) {
		if (spots.isEmpty() || !nametag.get()) return;
		long now = System.currentTimeMillis();
		int info = Colors.withAlpha(infoColor.argb(), 255);
		for (Spot spot : spots) {
			Seen s = spot.seen();
			double cy = s.y() + (fullHeight.get() ? s.height() : 0) + 0.5;
			double distance = projector.distance(s.x(), cy, s.z());
			if (distance > maxDistance.get()) continue;
			float[] point = projector.project(s.x(), cy, s.z());
			if (!projector.onScreen(point, 20)) continue;
			List<Nametag.Segment> segments = new ArrayList<>(6);
			if (showName.get()) add(segments, s.name(), Colors.withAlpha(nameColor.argb(), 255));
			if (showHealth.get()) {
				float ratio = s.maxHealth() <= 0 ? 1f : s.health() / (float) s.maxHealth();
				add(segments, String.valueOf(s.health()), ratio <= 0.333f ? RED : ratio <= 0.666f ? ORANGE : GREEN);
			}
			if (showEvent.get()) add(segments, switch (spot.kind()) {
				case LOGOUT -> "logout";
				case ENTER -> "entered";
				case LEAVE -> "left";
			}, Colors.withAlpha(color(spot.kind()), 255));
			if (showDistance.get()) add(segments, Math.round(distance) + "m", info);
			if (showAge.get()) add(segments, age(now - spot.createdAt()), info);
			if (showCoords.get()) add(segments, Coords.xyz(s.x(), s.y(), s.z()), info);
			if (segments.isEmpty()) continue;
			Nametag.drawSegments(ui, point[0], point[1], segments, scale.floatValue(), background.get(), nameBackground.argb(), null);
			if (showGear.get()) {
				float above = point[1] - 12 * scale.floatValue();
				Nametag.drawEquipment(ui, point[0], above, s.gear(), java.util.Collections.nCopies(s.gear().size(), List.of()),
					java.util.Collections.nCopies(s.gear().size(), ""), scale.floatValue(), 2, true, false, 1);
			}
		}
	}

	/** Appends a label part, space-separated from the previous one. */
	private static void add(List<Nametag.Segment> segments, String text, int color) {
		segments.add(new Nametag.Segment(segments.isEmpty() ? text : " " + text, color));
	}

	/** Compact elapsed time: 45s, 12m, 3h 5m. */
	private static String age(long millis) {
		long seconds = Math.max(0, millis / 1000);
		if (seconds < 60) return seconds + "s ago";
		long minutes = seconds / 60;
		if (minutes < 60) return minutes + "m ago";
		return minutes / 60 + "h " + minutes % 60 + "m ago";
	}

	@Override
	public String infoString() {
		return spots.isEmpty() ? null : String.valueOf(spots.size());
	}
}
