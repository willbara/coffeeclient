package dev.coffeeclient.module.modules.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.config.ConfigManager;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.ModuleHudElement;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.Tracers;
import dev.coffeeclient.util.WorldProjector;
import dev.coffeeclient.render.WorldOverlays;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.coffeeclient.module.modules.render.NaturalLoot;
import dev.coffeeclient.module.modules.world.stashfinder.Hub;
import dev.coffeeclient.module.modules.world.stashfinder.Kind;
import dev.coffeeclient.module.modules.world.stashfinder.Zones;
import dev.coffeeclient.module.modules.world.stashfinder.MiningTrails;
import dev.coffeeclient.module.modules.world.stashfinder.NaturalBlocks;
import dev.coffeeclient.render.OverlayRenderer;
import dev.coffeeclient.setting.ActionSetting;
import dev.coffeeclient.setting.BlockListSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.Async;
import dev.coffeeclient.util.Coords;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.Jingle;
import dev.coffeeclient.util.Sounds;
import dev.coffeeclient.util.JsonFiles;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Finds player storage by scanning block entities in loaded chunks, remembering the chunks per server
 * in {@code config/coffeeclient/stashes}.
 * Found chunks are shown with Coffee's existing tracer/label style and a HUD summary instead.
 *
 * <p>Also a base-hunting hub: detectors from for man-made blocks, dug tunnels, holes and
 * staircases, bedrock pockets, sky builds, Nether roof builds and mining trails, each with its own
 * toggle, run by {@link Hub}.
 */
public final class StashFinder extends Module {
	public enum Notify { Chat, Toast, Both, Off }

	private static final List<String> DEFAULT_TYPES = List.of(
		"minecraft:barrel", "minecraft:blast_furnace", "minecraft:chest", "minecraft:dispenser",
		"minecraft:dropper", "minecraft:ender_chest", "minecraft:furnace", "minecraft:hopper",
		"minecraft:shulker_box", "minecraft:smoker");

	public final IdListSetting types = group(new IdListSetting("Storage types", "Block entities counted as storage",
		DEFAULT_TYPES, () -> BuiltInRegistries.BLOCK_ENTITY_TYPE.keySet().stream()
		.map(Identifier::toString).sorted().toList(), false).labels(StashFinder::typeName), "Scanning");
	public final BooleanSetting containers = group(new BooleanSetting("Containers", "Find and save chunks full of storage (the original stash finder)", true), "Scanning");
	public final NumberSetting minCount = group(new NumberSetting("Min containers", "Ignore chunks with fewer containers", 4, 1, 64, 1), "Scanning");
	public final NumberSetting minDistance = group(new NumberSetting("Min distance", "Minimum distance from spawn (0, 0)", 0, 0, 10000, 16), "Scanning");
	public final BooleanSetting skipTrialChambers = group(new BooleanSetting("Skip trial chambers", "Don't count containers that look like trial chamber loot", false), "Scanning");
	public final BooleanSetting skipSpawnerRooms = group(new BooleanSetting("Skip spawner rooms", "Don't count chests next to a monster spawner (dungeons)", false), "Scanning");
	public final BooleanSetting skipVillages = group(new BooleanSetting("Skip village chests", "Don't count chests that look like village loot", false), "Scanning");
	public final NumberSetting rescanInterval = group(new NumberSetting("Rescan (ticks)", "How often nearby chunks are rescanned", 40, 10, 200, 10), "Scanning");

	public final EnumSetting<Notify> notification = group(new EnumSetting<>("Notify", "How new stashes are reported", Notify.Both), "Notifications");
	public final BooleanSetting ding = group(new BooleanSetting("Alert sound", "Play a sound when a stash or detector find is reported", false), "Notifications");
	public final EnumSetting<Jingle> alertSound = group(new EnumSetting<>("Sound", "Ding: three noteblock bells. Bell: the village bell. Fanfare: a rising pling tune", Jingle.Ding), "Notifications");
	public final NumberSetting dingVolume = group(new NumberSetting("Alert volume", "How loud the alert is", 0.6, 0.05, 1, 0.05).format(v -> Math.round(v * 100) + "%"), "Notifications");

	public final BooleanSetting stashTracers = group(new BooleanSetting("Stash tracers", "Draw a line to every recorded stash", true), "Tracers");
	public final ColorSetting tracerColor = group(new ColorSetting("Stash tracer color", "Colour of stash tracers and chunk columns. Detector finds use their own colours", 0xFF55FF55), "Tracers");
	public final EnumSetting<Tracers.Style> tracerStyle = group(new EnumSetting<>("Tracer style", "Applies to stash and detector tracers. Smooth: thin lines drawn in the world. Classic: Coffee's original pixel lines on the screen", Tracers.Style.Smooth), "Tracers");
	public final BooleanSetting detectorTracers = group(new BooleanSetting("Detector tracers", "Draw a line to the nearest Base hunting finds (one per chunk and kind), each in its find's colour", false), "Tracers");
	public final NumberSetting maxDistance = group(new NumberSetting("Hide stash distance", "Stop drawing a stash's tracer and column once you are farther than this (blocks)", 100, 32, 1000, 16), "Render");
	public final NumberSetting hideCloser = group(new NumberSetting("Hide closer than", "Hide stash tracers closer than this (the column still shows)", 16, 0, 256, 4), "Render");
	public final BooleanSetting chunkColumns = group(new BooleanSetting("Chunk columns", "Draw a column at each found chunk", false), "Render");
	public final BooleanSetting hudSummary = group(new BooleanSetting("HUD summary", "Show stash totals on the HUD", true), "Render");

	public final ActionSetting clear = group(new ActionSetting("Clear stashes", "Forget every stash for this server", this::clearAll), "Actions");

	// Base hunting: shared

	public final NumberSetting scanRadius = group(new NumberSetting("Scan radius (chunks)", "Detectors scan loaded chunks this far around you", 8, 2, 32, 1), "Base hunting");
	public final NumberSetting timeBudget = group(new NumberSetting("Time per tick (ms)", "Most time the detectors may use each tick", 2, 0.5, 10, 0.5), "Base hunting");
	public final BooleanSetting sticky = group(new BooleanSetting("Sticky area", "Keep finds after their chunk unloads or you move away", false), "Base hunting");
	public final NumberSetting maxPerChunk = group(new NumberSetting("Max per chunk", "Most finds of one kind kept per chunk", 64, 4, 1024, 4), "Base hunting");
	public final NumberSetting renderLimit = group(new NumberSetting("Render limit", "Most boxes drawn, nearest first", 5000, 100, 20000, 100), "Base hunting");
	public final EnumSetting<WorldOverlays.Shape> detectorShape = group(new EnumSetting<>("Box shape", "How detector finds are drawn", WorldOverlays.Shape.Both), "Base hunting");
	public final NumberSetting detectorOpacity = group(new NumberSetting("Box fill opacity", "Alpha of filled faces", 40, 0, 255, 5), "Base hunting");
	public final BooleanSetting detectorAlerts = group(new BooleanSetting("Detector alerts", "Report each new find with the Notify setting", false), "Base hunting");

	public final BooleanSetting manMadeBlocks = group(new BooleanSetting("Man-made blocks", "Highlight blocks that world generation doesn't place", false), "Man-made");
	public final BlockListSetting naturalBlocks = group(new BlockListSetting("Natural blocks", "Blocks never counted as man-made", NaturalBlocks.NATURAL), "Man-made");
	public final NumberSetting manMadeMinY = group(new NumberSetting("Min Y", "Lowest Y scanned", -64, -64, 320, 1), "Man-made");
	public final NumberSetting manMadeMaxY = group(new NumberSetting("Max Y", "Highest Y scanned", 320, -64, 320, 1), "Man-made");
	public final ColorSetting manMadeColor = group(new ColorSetting("Man-made color", "Colour of man-made blocks", 0xFFFF3030), "Man-made");

	public final BooleanSetting tunnels = group(new BooleanSetting("Tunnels and holes", "Find dug tunnels, holes, staircases and shafts", false), "Tunnels");
	public final BooleanSetting holes = group(new BooleanSetting("Holes", "Vertical 1x1 and 2x2 holes", true), "Tunnels");
	public final BooleanSetting straightTunnels = group(new BooleanSetting("Tunnels", "Straight tunnels", true), "Tunnels");
	public final BooleanSetting stairs = group(new BooleanSetting("Staircases", "Dug staircases", true), "Tunnels");
	public final BooleanSetting columns = group(new BooleanSetting("Columns", "Ladder, bubble and water columns in a 1x1 shaft", true), "Tunnels");
	public final NumberSetting minHoleDepth = group(new NumberSetting("Min hole depth", "Shortest hole found", 4, 1, 20, 1), "Tunnels");
	public final NumberSetting minTunnelLength = group(new NumberSetting("Min tunnel length", "Shortest tunnel found", 4, 2, 30, 1), "Tunnels");
	public final NumberSetting maxTunnelWidth = group(new NumberSetting("Max tunnel width", "Widest tunnel found", 3, 1, 8, 1), "Tunnels");
	public final NumberSetting maxTunnelHeight = group(new NumberSetting("Max tunnel height", "Tallest tunnel found", 3, 2, 8, 1), "Tunnels");
	public final NumberSetting minStairLength = group(new NumberSetting("Min stair length", "Fewest steps in a staircase", 4, 2, 30, 1), "Tunnels");
	public final NumberSetting minColumnHeight = group(new NumberSetting("Min column height", "Shortest ladder or water column", 5, 1, 64, 1), "Tunnels");
	public final BooleanSetting naturalWalls = group(new BooleanSetting("Natural wall filter", "Skip shapes whose walls mostly aren't terrain (structures, builds)", true), "Tunnels");
	public final NumberSetting naturalWallRatio = group(new NumberSetting("Natural wall ratio", "Share of wall blocks that must look like terrain", 0.7, 0.25, 1.0, 0.05), "Tunnels");
	public final ColorSetting holeColor = group(new ColorSetting("Hole color", "Colour of holes", 0xFFFF3C3C), "Tunnels");
	public final ColorSetting tunnelColor = group(new ColorSetting("Tunnel color", "Colour of tunnels", 0xFF468CFF), "Tunnels");
	public final ColorSetting stairColor = group(new ColorSetting("Stair color", "Colour of staircases", 0xFFFFC83C), "Tunnels");
	public final ColorSetting columnColor = group(new ColorSetting("Column color", "Colour of ladder and water columns", 0xFF3CDCFF), "Tunnels");

	public final BooleanSetting pockets = group(new BooleanSetting("Bedrock pockets", "Find spaces dug into the bedrock floor (and the Nether roof)", false), "Bedrock pockets");
	public final BooleanSetting pocketsAirOnly = group(new BooleanSetting("Air only", "Only empty pockets (natural ones are filled with stone)", true), "Bedrock pockets");
	public final ColorSetting pocketColor = group(new ColorSetting("Pocket color", "Colour of bedrock pockets", 0xFFB450FF), "Bedrock pockets");

	public final BooleanSetting skyBuilds = group(new BooleanSetting("Sky builds", "Find blocks placed high above the terrain", false), "Sky builds");
	public final NumberSetting skyY = group(new NumberSetting("Above Y", "Only blocks above this height", 200, 100, 320, 5), "Sky builds");
	public final ColorSetting skyColor = group(new ColorSetting("Sky color", "Colour of sky builds", 0xFF50FFFF), "Sky builds");

	public final BooleanSetting netherRoof = group(new BooleanSetting("Nether roof", "Find blocks and items on top of the Nether roof", false), "Nether roof");
	public final BooleanSetting roofIgnoreMushrooms = group(new BooleanSetting("Ignore mushrooms", "Mushrooms grow on the roof by themselves", true), "Nether roof");
	public final BooleanSetting roofLadders = group(new BooleanSetting("Ladders below", "Also ladders between Y 118 and 127 (the way up)", true), "Nether roof");
	public final BooleanSetting roofItems = group(new BooleanSetting("Dropped items", "Also items lying on the roof", true), "Nether roof");
	public final ColorSetting roofColor = group(new ColorSetting("Roof color", "Colour of roof finds", 0xFFFF8C00), "Nether roof");

	public final BooleanSetting signsOfTime = group(new BooleanSetting("Signs of time", "Score chunks for rotated deepslate, redstone and hoppers, and growths that only finish while a chunk stays loaded", false), "Signs of time");
	public final NumberSetting ageScore = group(new NumberSetting("Min score", "Score a chunk needs (rotated deepslate 3, hopper 2, redstone 1, full kelp or cocoa 0.5)", 10, 1, 100, 1), "Signs of time");
	public final ColorSetting ageColor = group(new ColorSetting("Signs color", "Colour of these finds", 0xFFC080FF), "Signs of time");

	public final BooleanSetting zones = group(new BooleanSetting("Zones", "Merge nearby chunks with finds into zones and score them by what was found", false), "Zones");
	public final NumberSetting zoneGap = group(new NumberSetting("Merge within", "Chunks with finds this many chunks apart join one zone", 2, 0, 8, 1), "Zones");
	public final NumberSetting zoneScore = group(new NumberSetting("Min zone score", "Score a zone needs (hole 1, tunnel 2, pocket 3, more finds add a little)", 4, 1, 50, 0.5), "Zones");
	public final BooleanSetting holdFinds = group(new BooleanSetting("Hold weak finds", "Don't draw or report finds until their zone reaches the score", true), "Zones");
	public final BooleanSetting zoneLabels = group(new BooleanSetting("Zone labels", "Label each scoring zone with its score and what was found", true), "Zones");
	public final ColorSetting zoneColor = group(new ColorSetting("Zone color", "Colour of zone labels", 0xFFFFD27F), "Zones");

	public final BooleanSetting miningTrails = group(new BooleanSetting("Mining trails", "Find lines of dropped items underground, left by someone mining", false), "Mining trails");
	public final NumberSetting trailJoin = group(new NumberSetting("Join distance", "Largest gap between drops in one line", 6, 2, 24, 1), "Mining trails");
	public final NumberSetting trailMinDrops = group(new NumberSetting("Min drops", "Fewest drops that make a line", 4, 2, 32, 1), "Mining trails");
	public final NumberSetting trailMaxY = group(new NumberSetting("Max Y", "Only drops at or below this height", 64, -64, 320, 1), "Mining trails");
	public final NumberSetting trailScanDistance = group(new NumberSetting("Scan distance", "Only drops this close to you", 64, 16, 256, 8), "Mining trails");
	public final NumberSetting trailLifetime = group(new NumberSetting("Lifetime (min)", "Forget a line after this long (0 = keep)", 30, 0, 120, 1), "Mining trails");
	public final ColorSetting trailColor = group(new ColorSetting("Trail color", "Colour of mining trails", 0xFFFFFF55), "Mining trails");

	private final Hub hub = new Hub(this);
	private final Map<Kind, Long> lastAlert = new EnumMap<>(Kind.class);
	/** Zones that reach the score, and which of them were already reported. */
	private List<Zones.Zone> scoredZones = List.of();
	private final java.util.Set<ChunkPos> reportedZones = new java.util.HashSet<>();
	private String hubDimension;
	private long builtVersion = -1;
	private List<AABB> detectorBoxes = List.of();
	private int[] detectorColors = new int[0];
	private List<Vec3> detectorTargets = List.of();
	private Jingle.Note[] alertNotes;
	private int alertIndex, dingWait;
	private List<Integer> detectorTargetColors = List.of();
	private List<AABB> roofItemBoxes = List.of();

	private record Stash(String dimension, int x, int z, int total, Map<String, Integer> counts) {
	}

	private final NaturalLoot loot = new NaturalLoot();
	private final Map<String, Stash> stashes = new LinkedHashMap<>();
	private final ModuleHudElement element = new ModuleHudElement(this, "stash_finder", "Stash finder",
		this::hudLines, (w, h) -> new int[] {2, h / 2});
	private String checkedServer;
	private int ticks;
	private boolean dirty;

	public StashFinder() {
		super("StashFinder", List.of("Stash Finder"), Category.WORLD, "Finds and records player storage chunks.");
		element.when(hudSummary::get);
		for (Setting<?> s : List.of(manMadeBlocks, naturalBlocks, manMadeMinY, manMadeMaxY, tunnels, holes, straightTunnels, stairs,
			columns, minHoleDepth, minTunnelLength, maxTunnelWidth, maxTunnelHeight, minStairLength, minColumnHeight, naturalWalls,
			naturalWallRatio, pockets, pocketsAirOnly, skyBuilds, skyY, netherRoof, roofIgnoreMushrooms, roofLadders, maxPerChunk, signsOfTime, ageScore)) {
			s.onChange(v -> hub.rescan());
		}
		for (Setting<?> s : List.of(naturalBlocks, manMadeMinY, manMadeMaxY, manMadeColor)) s.visibleWhen(manMadeBlocks::get);
		for (Setting<?> s : List.of(holes, straightTunnels, stairs, columns, minHoleDepth, minTunnelLength, maxTunnelWidth, maxTunnelHeight,
			minStairLength, minColumnHeight, naturalWalls, holeColor, tunnelColor, stairColor, columnColor)) s.visibleWhen(tunnels::get);
		naturalWallRatio.visibleWhen(() -> tunnels.get() && naturalWalls.get());
		for (Setting<?> s : List.of(pocketsAirOnly, pocketColor)) s.visibleWhen(pockets::get);
		for (Setting<?> s : List.of(skyY, skyColor)) s.visibleWhen(skyBuilds::get);
		for (Setting<?> s : List.of(roofIgnoreMushrooms, roofLadders, roofItems, roofColor)) s.visibleWhen(netherRoof::get);
		for (Setting<?> s : List.of(ageScore, ageColor)) s.visibleWhen(signsOfTime::get);
		for (Setting<?> s : List.of(zoneGap, zoneScore, holdFinds, zoneLabels, zoneColor)) s.visibleWhen(zones::get);
		for (Setting<?> s : List.of(zones, zoneGap, zoneScore, holdFinds)) s.onChange(v -> builtVersion = -1);
		for (Setting<?> s : List.of(trailJoin, trailMinDrops, trailMaxY, trailScanDistance, trailLifetime, trailColor)) s.visibleWhen(miningTrails::get);
		detectorOpacity.visibleWhen(() -> detectorShape.get() != WorldOverlays.Shape.Outline);
		for (Setting<?> s : List.of(minCount, minDistance, skipTrialChambers, skipSpawnerRooms, skipVillages, rescanInterval)) s.visibleWhen(containers::get);
		for (Setting<?> s : List.of(alertSound, dingVolume)) s.visibleWhen(ding::get);

		listen(Events.Tick.class, e -> {
			if (alertNotes == null || --dingWait > 0) return;
			Jingle.Note note = alertNotes[alertIndex++];
			Sounds.play(note.sound(), note.pitch(), dingVolume.floatValue());
			dingWait = note.gap();
			if (alertIndex >= alertNotes.length) alertNotes = null;
		});

		listen(Events.ChunkLoad.class, e -> hub.chunkLoaded(e.chunk().getPos()));
		listen(Events.ChunkUnload.class, e -> hub.chunkUnloaded(e.chunk().getPos()));
		listen(Events.BlockUpdate.class, e -> hub.blockChanged(e.pos()));
		listen(Events.BlockBroken.class, e -> hub.blockBroken(e.pos()));
	}

	/** The base-hunting detectors (for tests and the HUD). */
	public Hub hub() {
		return hub;
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void init(EventBus bus) {
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			if (!isEnabled()) return;
			Minecraft.getInstance().execute(() -> scanChunk(chunk));
		});
	}

	@Override
	protected void onDisable() {
		save();
		stashes.clear();
		checkedServer = null;
		hub.clear();
		hubDimension = null;
		scoredZones = List.of();
		reportedZones.clear();
		detectorBoxes = List.of();
		detectorTargets = List.of();
		roofItemBoxes = List.of();
		builtVersion = -1;
	}

	@Override
	public List<HudElement> hudElements() {
		return List.of(element);
	}

	@Override
	public void onTick() {
		Minecraft mc = mc();
		if (mc.level == null || mc.player == null) return;

		String server = serverKey(mc);
		if (!Objects.equals(server, checkedServer)) {
			save();
			checkedServer = server;
			stashes.clear();
			dirty = false;
			load(server);
		}

		if (++ticks % rescanInterval.intValue() == 0) {
			int cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z();
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					var chunk = mc.level.getChunkSource().getChunk(cx + dx, cz + dz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
					if (chunk instanceof LevelChunk levelChunk) scanChunk(levelChunk);
				}
			}
		}

		if (dirty && ticks % 100 == 0) save();

		String dimension = mc.level.dimension().identifier().toString();
		if (!dimension.equals(hubDimension)) {
			hubDimension = dimension;
			hub.clear();
		}
		for (Hub.Found found : hub.tick(mc.level, mc.player)) alert(found);
		roofItemBoxes = netherRoof.get() && roofItems.get() && mc.level.dimension() == Level.NETHER ? roofItems(mc) : List.of();
		if (hub.version() != builtVersion || ticks % 20 == 0) {
			builtVersion = hub.version();
			updateZones();
			rebuildDetectorRender(mc.player.position());
		}
	}

	/** Rebuilds the scoring zones, reporting each the first time it passes the score. */
	private void updateZones() {
		if (!zones.get()) {
			scoredZones = List.of();
			return;
		}
		List<Zones.Zone> passing = new ArrayList<>();
		for (Zones.Zone z : Zones.build(hub.hits(), zoneGap.intValue())) if (z.score() >= zoneScore.get()) passing.add(z);
		scoredZones = passing;
		for (Zones.Zone z : passing) {
			if (!detectorAlerts.get() || !reportedZones.add(z.first())) continue;
			report(String.format(java.util.Locale.ROOT, "Zone (score %.1f: %s) at %d, %d", z.score(), kindNames(z), (int) z.centerX(), (int) z.centerZ()));
		}
	}

	private static String kindNames(Zones.Zone z) {
		return String.join(", ", z.kinds().stream().map(k -> k.label).toList());
	}

	/** With Hold weak finds, a chunk counts only while it is part of a scoring zone. */
	private boolean shown(ChunkPos chunk) {
		if (!zones.get() || !holdFinds.get()) return true;
		for (Zones.Zone z : scoredZones) if (z.chunks().contains(chunk)) return true;
		return false;
	}

	private void alert(Hub.Found found) {
		if (!detectorAlerts.get() || zones.get() && holdFinds.get()) return;
		long now = System.currentTimeMillis();
		Long last = lastAlert.get(found.kind());
		if (last != null && now - last < 5000) return;
		lastAlert.put(found.kind(), now);
		AABB box = found.box();
		report(found.kind().label + " at " + (int) Math.floor(box.minX) + ", " + (int) Math.floor(box.minY) + ", " + (int) Math.floor(box.minZ));
	}

	private List<AABB> roofItems(Minecraft mc) {
		List<AABB> boxes = new ArrayList<>();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof ItemEntity item) || item.getY() < 128) continue;
			if (item.getItem().is(Blocks.RED_MUSHROOM.asItem()) || item.getItem().is(Blocks.BROWN_MUSHROOM.asItem())) continue;
			boxes.add(item.getBoundingBox());
		}
		return boxes;
	}

	public int colorOf(Kind kind) {
		return switch (kind) {
			case MAN_MADE -> manMadeColor.argb();
			case HOLE -> holeColor.argb();
			case TUNNEL -> tunnelColor.argb();
			case STAIR -> stairColor.argb();
			case COLUMN -> columnColor.argb();
			case POCKET -> pocketColor.argb();
			case SKY -> skyColor.argb();
			case ROOF -> roofColor.argb();
			case TRAIL -> trailColor.argb();
			case AGE -> ageColor.argb();
		} & 0xFFFFFF;
	}

	/** Detector boxes nearest first, capped, plus one tracer target per chunk and kind. */
	private void rebuildDetectorRender(Vec3 eye) {
		record Box(AABB box, int color, double distance) {
		}
		List<Box> all = new ArrayList<>();
		List<Box> targets = new ArrayList<>();
		for (Map.Entry<ChunkPos, Map<Kind, List<AABB>>> chunk : hub.hits().entrySet()) {
			if (!shown(chunk.getKey())) continue;
			for (Map.Entry<Kind, List<AABB>> e : chunk.getValue().entrySet()) {
				int color = colorOf(e.getKey());
				Box nearest = null;
				for (AABB box : e.getValue()) {
					Box b = new Box(box, color, box.getCenter().distanceToSqr(eye));
					all.add(b);
					if (nearest == null || b.distance() < nearest.distance()) nearest = b;
				}
				if (nearest != null) targets.add(nearest);
			}
		}
		all.sort(Comparator.comparingDouble(Box::distance));
		if (all.size() > renderLimit.intValue()) all = all.subList(0, renderLimit.intValue());
		targets.sort(Comparator.comparingDouble(Box::distance));
		if (targets.size() > 32) targets = targets.subList(0, 32);
		List<AABB> boxes = new ArrayList<>(all.size());
		int[] colors = new int[all.size()];
		for (Box b : all) {
			colors[boxes.size()] = b.color();
			boxes.add(b.box());
		}
		detectorBoxes = boxes;
		detectorColors = colors;
		detectorTargets = targets.stream().map(b -> b.box().getCenter()).toList();
		detectorTargetColors = targets.stream().map(Box::color).toList();
	}

	/** Scans one chunk and records, updates or removes its stash entry. */
	private void scanChunk(LevelChunk chunk) {
		Minecraft mc = mc();
		if (!isEnabled() || !containers.get() || mc.level == null || chunk.isEmpty()) return;

		String dimension = mc.level.dimension().identifier().toString();
		ChunkPos pos = chunk.getPos();
		Map<String, Integer> counts = new TreeMap<>();
		boolean skipNatural = skipTrialChambers.get() || skipSpawnerRooms.get() || skipVillages.get();
		if (skipNatural) loot.beginScan(mc.level);
		for (BlockEntity entity : chunk.getBlockEntities().values()) {
			Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType());
			if (id == null) continue;
			String key = id.toString();
			if (!types.contains(key)) continue;
			if (skipNatural && loot.hide(mc.level, entity, skipSpawnerRooms.get(), skipTrialChambers.get(), skipVillages.get())) continue;
			counts.merge(key, 1, Integer::sum);
		}
		int total = counts.values().stream().mapToInt(Integer::intValue).sum();
		double spawnDistance = Math.hypot(pos.getMinBlockX() + 8.0, pos.getMinBlockZ() + 8.0);
		boolean tooClose = spawnDistance < minDistance.get();
		String key = dimension + "|" + pos.x() + "," + pos.z();

		if (total < minCount.intValue() || tooClose) {
			if (stashes.remove(key) != null) dirty = true;
			return;
		}
		Stash stash = new Stash(dimension, pos.x(), pos.z(), total, Map.copyOf(counts));
		Stash previous = stashes.get(key);
		if (stash.equals(previous)) return;
		stashes.put(key, stash);
		dirty = true;
		if (previous == null) report("Found stash at " + centerX(stash) + ", " + centerZ(stash) + " (" + total + " containers)");
		else report("Stash at " + centerX(stash) + ", " + centerZ(stash) + " now has " + total + " containers");
	}

	private void report(String text) {
		if (ding.get()) {
			alertNotes = alertSound.get().notes;
			alertIndex = 0;
			dingWait = 0;
		}
		switch (notification.get()) {
			case Chat -> info(text);
			case Toast -> notify(text);
			case Both -> {
				info(text);
				notify(text);
			}
			case Off -> {
			}
		}
	}

	private void clearAll() {
		stashes.clear();
		dirty = true;
		save();
	}

	private String serverKey(Minecraft mc) {
		String server = "singleplayer";
		if (mc.getConnection() != null) {
			ServerData data = mc.getConnection().getServerData();
			if (data != null && data.ip != null && !data.ip.isBlank()) server = data.ip;
			else if (data != null && data.name != null && !data.name.isBlank()) server = data.name;
		}
		return sanitize(server);
	}

	private static String sanitize(String value) {
		String clean = value.replaceAll("[^A-Za-z0-9._-]", "_");
		return clean.length() > 64 ? clean.substring(0, 64) : clean;
	}

	private static Path file(String server) {
		ConfigManager config = CoffeeClient.config();
		return config.directory().resolve("stashes").resolve(server + ".json");
	}

	private void load(String server) {
		Path path = file(server);
		Async.io(() -> {
			Map<String, Stash> loaded = read(path);
			if (loaded.isEmpty()) return;
			Minecraft.getInstance().execute(() -> {
				if (!server.equals(checkedServer)) return;
				// A save may have run before this merge and written only the new stashes: save again.
				loaded.forEach((key, stash) -> {
					if (stashes.putIfAbsent(key, stash) == null) dirty = true;
				});
			});
		});
	}

	private static Map<String, Stash> read(Path path) {
		Map<String, Stash> result = new LinkedHashMap<>();
		JsonObject root = JsonFiles.read(path);
		if (root == null || !root.has("stashes") || !root.get("stashes").isJsonArray()) return result;
		int skipped = 0;
		for (JsonElement element : root.getAsJsonArray("stashes")) {
			if (!element.isJsonObject()) {
				skipped++;
				continue;
			}
			try {
				JsonObject object = element.getAsJsonObject();
				String dimension = object.get("dim").getAsString();
				int x = object.get("x").getAsInt();
				int z = object.get("z").getAsInt();
				int total = object.get("total").getAsInt();
				Map<String, Integer> counts = new TreeMap<>();
				if (object.has("counts") && object.get("counts").isJsonObject()) {
					for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("counts").entrySet()) {
						counts.put(entry.getKey(), entry.getValue().getAsInt());
					}
				}
				result.put(dimension + "|" + x + "," + z, new Stash(dimension, x, z, total, Map.copyOf(counts)));
			} catch (RuntimeException e) {
				// skip a malformed row rather than losing the whole file
				skipped++;
			}
		}
		if (skipped > 0) CoffeeClient.LOG.warn("[StashFinder] Skipped {} unreadable rows in {}", skipped, path.getFileName());
		return result;
	}

	private void save() {
		if (!dirty || checkedServer == null) return;
		dirty = false;
		String server = checkedServer;
		JsonObject root = new JsonObject();
		JsonArray array = new JsonArray();
		for (Stash stash : stashes.values()) {
			JsonObject object = new JsonObject();
			object.addProperty("dim", stash.dimension());
			object.addProperty("x", stash.x());
			object.addProperty("z", stash.z());
			object.addProperty("total", stash.total());
			JsonObject counts = new JsonObject();
			stash.counts().forEach(counts::addProperty);
			object.add("counts", counts);
			array.add(object);
		}
		root.add("stashes", array);
		Path path = file(server);
		Async.io(() -> {
			try {
				JsonFiles.writeAtomic(path, JsonFiles.GSON.toJson(root));
			} catch (IOException e) {
				CoffeeClient.LOG.warn("Could not save stashes to {}: {}", path, e.getMessage());
			}
		});
	}

	private static String typeName(String id) {
		String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		String[] parts = path.split("_");
		StringBuilder name = new StringBuilder();
		for (String part : parts) {
			if (part.isEmpty()) continue;
			if (!name.isEmpty()) name.append(' ');
			name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
		}
		return name.toString();
	}

	private static int centerX(Stash stash) {
		return stash.x() * 16 + 8;
	}

	private static int centerZ(Stash stash) {
		return stash.z() * 16 + 8;
	}

	/** The nearest stash in the current dimension, or null. */
	private Stash nearest() {
		Minecraft mc = mc();
		if (mc.level == null || mc.player == null) return null;
		String dimension = mc.level.dimension().identifier().toString();
		Stash best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Stash stash : stashes.values()) {
			if (!stash.dimension().equals(dimension)) continue;
			double dx = centerX(stash) - mc.player.getX();
			double dz = centerZ(stash) - mc.player.getZ();
			double distance = dx * dx + dz * dz;
			if (distance < bestDistance) {
				bestDistance = distance;
				best = stash;
			}
		}
		return best;
	}

	private List<String[]> hudLines() {
		Minecraft mc = mc();
		List<String[]> lines = new ArrayList<>();
		String dimension = mc.level == null ? "" : mc.level.dimension().identifier().toString();
		long count = stashes.values().stream().filter(stash -> stash.dimension().equals(dimension)).count();
		lines.add(ModuleHudElement.row("Stashes", count));
		int finds = 0;
		for (Map<Kind, List<AABB>> perChunk : hub.hits().values()) for (List<AABB> list : perChunk.values()) finds += list.size();
		if (finds > 0) lines.add(ModuleHudElement.row("Base finds", finds));
		Stash nearest = nearest();
		if (nearest != null) {
			Minecraft client = mc();
			double distance = client.player == null ? 0 : Math.hypot(centerX(nearest) - client.player.getX(), centerZ(nearest) - client.player.getZ());
			lines.add(ModuleHudElement.row("Nearest", Coords.format(centerX(nearest)) + ", " + Coords.format(centerZ(nearest)) + String.format(Locale.ROOT, " (%.0fm)", distance)));
		}
		return lines;
	}

	@Override
	public void renderWorld(PoseStack poseStack, Vec3 cameraPos) {
		Minecraft mc = mc();
		if (mc.level == null || mc.player == null) return;
		renderDetectors(mc, cameraPos);
		String dimension = mc.level.dimension().identifier().toString();
		int argb = 0xFF000000 | (tracerColor.argb() & 0xFFFFFF);
		float height = mc.level.getHeight();
		float bottom = mc.level.getMinY();
		if (chunkColumns.get()) {
			for (Stash stash : stashes.values()) {
				if (!stash.dimension().equals(dimension)) continue;
				double dx = centerX(stash) + 0.5 - cameraPos.x;
				double dz = centerZ(stash) + 0.5 - cameraPos.z;
				if (Math.hypot(dx, dz) > maxDistance.get()) continue;
				poseStack.pushPose();
				poseStack.translate(dx, bottom - cameraPos.y, dz);
				poseStack.scale(0.5f, height, 0.5f);
				WorldOverlays.cube(poseStack, WorldOverlays.Shape.Outline, true, argb, 0, 1f);
				poseStack.popPose();
			}
		}
		// Tracers at the player's height, so they read as a heading on the horizon.
		if (!stashTracers.get() || tracerStyle.get() != Tracers.Style.Smooth) return;
		VertexConsumer tracers = null;
		int tracerArgb = Colors.withAlpha(tracerColor.argb(), 0x99);
		double y = mc.player.getY();
		for (Stash stash : stashes.values()) {
			if (!stash.dimension().equals(dimension)) continue;
			double x = centerX(stash) + 0.5, z = centerZ(stash) + 0.5;
			double distance = cameraPos.distanceTo(new Vec3(x, y, z));
			if (distance > maxDistance.get() || distance < hideCloser.get()) continue;
			if (tracers == null) tracers = EspRender.tracerBuffer();
			EspRender.tracer(tracers, x, y, z, tracerArgb, 1f, cameraPos);
		}
	}

	private void renderDetectors(Minecraft mc, Vec3 cameraPos) {
		WorldOverlays.Shape shape = detectorShape.get();
		EspRender.boxes(detectorBoxes, detectorColors, shape, true, 1.5f, detectorOpacity.intValue(), cameraPos);
		if (!roofItemBoxes.isEmpty()) {
			int[] colors = new int[roofItemBoxes.size()];
			java.util.Arrays.fill(colors, roofColor.argb() & 0xFFFFFF);
			EspRender.boxes(roofItemBoxes, colors, shape, true, 1.5f, detectorOpacity.intValue(), cameraPos);
		}
		if (miningTrails.get()) {
			List<MiningTrails.Trail> trails = hub.trails(mc.level.dimension().identifier().toString());
			if (!trails.isEmpty()) {
				int argb = 0xFF000000 | (trailColor.argb() & 0xFFFFFF);
				VertexConsumer lines = OverlayRenderer.buffer(WorldOverlays.outlineType(true));
				List<AABB> markers = new ArrayList<>();
				for (MiningTrails.Trail trail : trails) {
					Vec3 previous = null;
					for (Vec3 p : trail.points()) {
						markers.add(new AABB(p.x - 0.2, p.y, p.z - 0.2, p.x + 0.2, p.y + 0.4, p.z + 0.2));
						if (previous != null) line(lines, previous, p, argb, cameraPos);
						previous = p;
					}
				}
				int[] colors = new int[markers.size()];
				java.util.Arrays.fill(colors, argb & 0xFFFFFF);
				EspRender.boxes(markers, colors, WorldOverlays.Shape.Outline, true, 1f, 0, cameraPos);
			}
		}
		if (detectorTracers.get() && tracerStyle.get() == Tracers.Style.Smooth && !detectorTargets.isEmpty()) {
			VertexConsumer tracers = EspRender.tracerBuffer();
			for (int i = 0; i < detectorTargets.size(); i++) {
				Vec3 t = detectorTargets.get(i);
				EspRender.tracer(tracers, t.x, t.y, t.z, 0x99000000 | detectorTargetColors.get(i), 1f, cameraPos);
			}
		}
	}

	private static void line(VertexConsumer lines, Vec3 a, Vec3 b, int argb, Vec3 cam) {
		float ax = (float) (a.x - cam.x), ay = (float) (a.y + 0.2 - cam.y), az = (float) (a.z - cam.z);
		float bx = (float) (b.x - cam.x), by = (float) (b.y + 0.2 - cam.y), bz = (float) (b.z - cam.z);
		float nx = bx - ax, ny = by - ay, nz = bz - az;
		float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (length < 1e-4f) return;
		WorldOverlays.line(lines, ax, ay, az, bx, by, bz, nx / length, ny / length, nz / length, argb, 2f);
	}

	@Override
	public void renderOverlay(Ui ui, WorldProjector projector) {
		if (zones.get() && zoneLabels.get() && mc().player != null) {
			for (Zones.Zone z : scoredZones) {
				float[] p = projector.project(z.centerX(), mc().player.getY() + 8, z.centerZ());
				if (!projector.onScreen(p, 30)) continue;
				String text = String.format(java.util.Locale.ROOT, "Zone %.1f: %s", z.score(), kindNames(z));
				dev.coffeeclient.util.Nametag.draw(ui, p[0], p[1], List.of(new dev.coffeeclient.util.Nametag.Line(text, 0xFF000000 | zoneColor.argb())),
					1, true, ui.theme.panel, -1, 0, null);
			}
		}
		Minecraft mc = mc();
		if (detectorTracers.get() && tracerStyle.get() == Tracers.Style.Classic) {
			for (int i = 0; i < detectorTargets.size(); i++) {
				Vec3 t = detectorTargets.get(i);
				Tracers.pixel(ui, projector, t.x, t.y, t.z, 0x99000000 | detectorTargetColors.get(i), 1);
			}
		}
		if (!stashTracers.get() || tracerStyle.get() != Tracers.Style.Classic || mc.level == null || mc.player == null) return;
		String dimension = mc.level.dimension().identifier().toString();
		int argb = Colors.withAlpha(tracerColor.argb(), 0x99);
		for (Stash stash : stashes.values()) {
			if (!stash.dimension().equals(dimension)) continue;
			double x = centerX(stash) + 0.5, z = centerZ(stash) + 0.5;
			double distance = projector.distance(x, mc.player.getY(), z);
			if (distance > maxDistance.get() || distance < hideCloser.get()) continue;
			Tracers.pixel(ui, projector, x, mc.player.getY(), z, argb, 1);
		}
	}
}
