package dev.coffeeclient.module.modules.world;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.config.ConfigManager;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.screen.StashHistoryPanel;
import dev.coffeeclient.gui.widget.Widget;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.ModuleHudElement;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.Tracers;
import dev.coffeeclient.util.WorldProjector;
import dev.coffeeclient.render.WorldOverlays;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.coffeeclient.module.modules.render.NaturalLoot;
import dev.coffeeclient.module.modules.world.stashfinder.HistoryRow;
import dev.coffeeclient.module.modules.world.stashfinder.Hub;
import dev.coffeeclient.module.modules.world.stashfinder.Kind;
import dev.coffeeclient.module.modules.world.stashfinder.StashHistory;
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
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Finds player storage by scanning block entities in loaded chunks, remembering stashes and
 * base-hunting finds per server in {@code config/coffeeclient/stashes}.
 * Found chunks are shown with Coffee's existing tracer/label style and a HUD summary instead.
 *
 * <p>Also a base-hunting hub: detectors from for man-made blocks, dug tunnels, holes and
 * staircases, bedrock pockets, sky builds, Nether roof builds and mining trails, each with its own
 * toggle, run by {@link Hub}.
 */
public final class StashFinder extends Module {
	public enum Notify { Chat, Toast, Both, Off }

	/** How often a continuously visible row refreshes its last-seen time. */
	private static final long SEEN_REFRESH_MS = 30_000L;
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

	public final ActionSetting clear = group(new ActionSetting("Clear history", "Forget every remembered stash and find for this server", this::clearAll), "Actions");

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

	private final NaturalLoot loot = new NaturalLoot();
	private final Map<String, StashHistory.Stash> stashes = new LinkedHashMap<>();
	private final Map<String, StashHistory.Find> finds = new LinkedHashMap<>();
	private final ModuleHudElement element = new ModuleHudElement(this, "stash_finder", "Stash finder",
		this::hudLines, (w, h) -> new int[] {2, h / 2});
	private StashHistoryPanel historyPanel;
	private long enabledAt = System.currentTimeMillis();
	private String checkedServer;
	private String focused;
	private long historyVersion;
	private long historyGeneration;
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
	public Widget sidePanel() {
		ensureHistoryLoaded();
		if (historyPanel == null) historyPanel = new StashHistoryPanel(this);
		return historyPanel;
	}

	@Override
	protected void init(EventBus bus) {
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			if (!isEnabled()) return;
			Minecraft.getInstance().execute(() -> scanChunk(chunk));
		});
	}

	@Override
	protected void onEnable() {
		enabledAt = System.currentTimeMillis();
	}

	@Override
	protected void onDisable() {
		save();
		stashes.clear();
		finds.clear();
		focused = null;
		checkedServer = null;
		historyVersion++;
		historyGeneration++;
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

		ensureServer(mc);

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

	/**
	 * Scans one chunk and records or updates its stash entry. A chunk that no longer qualifies stays in
	 * history with its new count, but is not drawn or reported as an active stash.
	 */
	private void scanChunk(LevelChunk chunk) {
		Minecraft mc = mc();
		if (!isEnabled() || !containers.get() || mc.level == null || chunk.isEmpty()) return;
		// A chunk can load before the first tick; load this server's history before recording into it.
		ensureServer(mc);

		String dimension = mc.level.dimension().identifier().toString();
		ChunkPos pos = chunk.getPos();
		Map<String, Integer> counts = new TreeMap<>();
		long ySum = 0;
		int yCount = 0;
		boolean skipNatural = skipTrialChambers.get() || skipSpawnerRooms.get() || skipVillages.get();
		if (skipNatural) loot.beginScan(mc.level);
		for (BlockEntity entity : chunk.getBlockEntities().values()) {
			Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType());
			if (id == null) continue;
			String key = id.toString();
			if (!types.contains(key)) continue;
			if (skipNatural && loot.hide(mc.level, entity, skipSpawnerRooms.get(), skipTrialChambers.get(), skipVillages.get())) continue;
			counts.merge(key, 1, Integer::sum);
			ySum += entity.getBlockPos().getY();
			yCount++;
		}
		int total = counts.values().stream().mapToInt(Integer::intValue).sum();
		double spawnDistance = Math.hypot(pos.getMinBlockX() + 8.0, pos.getMinBlockZ() + 8.0);
		boolean tooClose = spawnDistance < minDistance.get();
		boolean qualifies = total >= minCount.intValue() && !tooClose;
		String key = StashHistory.stashKey(dimension, pos.x(), pos.z());
		StashHistory.Stash previous = stashes.get(key);
		if (previous == null && !qualifies) return;

		int y = yCount == 0 ? previous == null ? 64 : previous.y() : (int) Math.round((double) ySum / yCount);
		long now = System.currentTimeMillis();
		long lastSeen = previous == null || now - previous.lastSeen() >= SEEN_REFRESH_MS ? now : previous.lastSeen();
		StashHistory.Stash current = new StashHistory.Stash(dimension, pos.x(), pos.z(), y, total, Map.copyOf(counts),
			now, lastSeen, StashHistory.State.NEW);
		StashHistory.Stash merged = previous == null ? current : StashHistory.merge(previous, current);
		if (merged.equals(previous)) return;
		stashes.put(key, merged);
		dirty = true;
		historyVersion++;
		if (qualifies && (previous == null || previous.total() != total)) {
			if (previous == null) report("Found stash at " + merged.centerX() + ", " + merged.centerZ() + " (" + total + " containers)");
			else report("Stash at " + merged.centerX() + ", " + merged.centerZ() + " now has " + total + " containers");
		}
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
		finds.clear();
		focused = null;
		dirty = true;
		historyVersion++;
		historyGeneration++;
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

	/** Loads this server's history when the config screen is opened without the module running. */
	private void ensureHistoryLoaded() {
		Minecraft mc = mc();
		if (mc.level != null) ensureServer(mc);
	}

	private void ensureServer(Minecraft mc) {
		String server = serverKey(mc);
		if (Objects.equals(server, checkedServer)) return;
		save();
		checkedServer = server;
		stashes.clear();
		finds.clear();
		focused = null;
		dirty = false;
		historyVersion++;
		long generation = ++historyGeneration;
		load(server, generation);
	}

	private void load(String server, long generation) {
		Path path = file(server);
		Async.io(() -> {
			StashHistory.Loaded loaded = StashHistory.read(path);
			if (loaded.isEmpty()) return;
			Minecraft.getInstance().execute(() -> {
				if (!server.equals(checkedServer) || generation != historyGeneration) return;
				boolean changed = false;
				for (StashHistory.Stash stash : loaded.stashes().values()) {
					StashHistory.Stash existing = stashes.get(stash.key());
					StashHistory.Stash merged = existing == null ? stash : StashHistory.merge(stash, existing);
					if (merged.equals(existing)) continue;
					stashes.put(stash.key(), merged);
					changed = true;
				}
				for (StashHistory.Find find : loaded.finds().values()) {
					StashHistory.Find existing = finds.get(find.key());
					StashHistory.Find merged = existing == null ? find : StashHistory.merge(find, existing);
					if (merged.equals(existing)) continue;
					finds.put(find.key(), merged);
					changed = true;
				}
				// A save may have run before this merge and written only the new rows: save again.
				if (changed) {
					dirty = true;
					historyVersion++;
				}
			});
		});
	}

	private void save() {
		if (!dirty || checkedServer == null) return;
		dirty = false;
		String server = checkedServer;
		// The rows are immutable records, so a shallow copy is a safe snapshot; the JSON is built off the main thread.
		Map<String, StashHistory.Stash> stashSnapshot = new LinkedHashMap<>(stashes);
		Map<String, StashHistory.Find> findSnapshot = new LinkedHashMap<>(finds);
		Path path = file(server);
		Async.io(() -> {
			try {
				JsonFiles.writeAtomic(path, StashHistory.toJson(stashSnapshot, findSnapshot));
			} catch (IOException e) {
				CoffeeClient.LOG.warn("Could not save stash history to {}: {}", path, e.getMessage());
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

	/** Records one row per detector kind in a scanned chunk, keeping the first time it was seen. */
	public void rememberFinds(String dimension, ChunkPos chunk, Map<Kind, List<AABB>> result) {
		if (result.isEmpty()) return;
		long now = System.currentTimeMillis();
		boolean changed = false;
		for (Map.Entry<Kind, List<AABB>> entry : result.entrySet()) {
			List<AABB> boxes = entry.getValue();
			if (boxes.isEmpty()) continue;
			// The first box, as Hub reports it: an average of scattered finds can land on nothing.
			Vec3 center = boxes.getFirst().getCenter();
			int bx = (int) Math.floor(center.x);
			int by = (int) Math.floor(center.y);
			int bz = (int) Math.floor(center.z);
			String key = StashHistory.findKey(dimension, entry.getKey(), chunk.x(), chunk.z());
			StashHistory.Find previous = finds.get(key);
			long lastSeen = previous == null || now - previous.lastSeen() >= SEEN_REFRESH_MS ? now : previous.lastSeen();
			StashHistory.Find current = new StashHistory.Find(dimension, entry.getKey(), bx, by, bz, boxes.size(),
				now, lastSeen, StashHistory.State.NEW);
			StashHistory.Find merged = previous == null ? current : StashHistory.merge(previous, current);
			if (merged.equals(previous)) continue;
			finds.put(key, merged);
			changed = true;
		}
		if (changed) {
			dirty = true;
			historyVersion++;
		}
	}

	/** Records a mining trail's first drop, one row per chunk and kind. */
	public void rememberTrail(String dimension, Vec3 point) {
		int x = (int) Math.floor(point.x);
		int y = (int) Math.floor(point.y);
		int z = (int) Math.floor(point.z);
		String key = StashHistory.findKey(dimension, Kind.TRAIL, x >> 4, z >> 4);
		StashHistory.Find previous = finds.get(key);
		long now = System.currentTimeMillis();
		int count = previous == null ? 1 : previous.count() + 1;
		long lastSeen = previous == null || now - previous.lastSeen() >= SEEN_REFRESH_MS ? now : previous.lastSeen();
		StashHistory.Find current = new StashHistory.Find(dimension, Kind.TRAIL, x, y, z, count,
			now, lastSeen, StashHistory.State.NEW);
		StashHistory.Find merged = previous == null ? current : StashHistory.merge(previous, current);
		if (merged.equals(previous)) return;
		finds.put(key, merged);
		dirty = true;
		historyVersion++;
	}

	/** Every remembered stash and find for this server, for the history panel. */
	public List<HistoryRow> historyRows() {
		ensureHistoryLoaded();
		List<HistoryRow> rows = new ArrayList<>(stashes.size() + finds.size());
		for (StashHistory.Stash stash : stashes.values()) rows.add(stashRow(stash));
		for (StashHistory.Find find : finds.values()) rows.add(findRow(find));
		return rows;
	}

	public int historyCount() {
		ensureHistoryLoaded();
		return stashes.size() + finds.size();
	}

	public long historyVersion() {
		ensureHistoryLoaded();
		return historyVersion;
	}

	/** Cycles a row through new, visited and cleared, then saves it. */
	public void cycleState(String key) {
		StashHistory.Stash stash = stashes.get(key);
		if (stash != null) {
			stashes.put(key, new StashHistory.Stash(stash.dimension(), stash.x(), stash.z(), stash.y(), stash.total(),
				stash.counts(), stash.firstSeen(), stash.lastSeen(), stash.state().next()));
		} else {
			StashHistory.Find find = finds.get(key);
			if (find == null) return;
			finds.put(key, new StashHistory.Find(find.dimension(), find.kind(), find.x(), find.y(), find.z(), find.count(),
				find.firstSeen(), find.lastSeen(), find.state().next()));
		}
		dirty = true;
		historyVersion++;
		save();
	}

	/** Focuses one row's tracer, or stops focusing it when it already is. */
	public void focus(String key) {
		focused = key.equals(focused) ? null : key;
		historyVersion++;
	}

	public boolean isFocused(String key) {
		return key.equals(focused);
	}

	private static HistoryRow stashRow(StashHistory.Stash stash) {
		return new HistoryRow(stash.key(), stash.dimension(), null, stash.centerX(), stash.y(), stash.centerZ(),
			stash.total(), stashDetail(stash), stash.firstSeen(), stash.lastSeen(), stash.state());
	}

	private static HistoryRow findRow(StashHistory.Find find) {
		return new HistoryRow(find.key(), find.dimension(), find.kind(), find.x(), find.y(), find.z(), find.count(),
			findDetail(find), find.firstSeen(), find.lastSeen(), find.state());
	}

	private HistoryRow focusedRow() {
		StashHistory.Stash stash = stashes.get(focused);
		if (stash != null) return stashRow(stash);
		StashHistory.Find find = finds.get(focused);
		return find == null ? null : findRow(find);
	}

	private static String stashDetail(StashHistory.Stash stash) {
		StringBuilder detail = new StringBuilder();
		detail.append(stash.total()).append(stash.total() == 1 ? " container" : " containers");
		List<Map.Entry<String, Integer>> top = new ArrayList<>(stash.counts().entrySet());
		top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
		if (!top.isEmpty()) {
			detail.append(" · ");
			int shown = Math.min(3, top.size());
			for (int i = 0; i < shown; i++) {
				if (i > 0) detail.append(", ");
				Map.Entry<String, Integer> entry = top.get(i);
				detail.append(typeName(entry.getKey())).append(' ').append(entry.getValue());
			}
			if (top.size() > shown) detail.append(" +").append(top.size() - shown);
		}
		return detail.toString();
	}

	private static String findDetail(StashHistory.Find find) {
		String unit = find.kind() == Kind.TRAIL ? "drop" : "find";
		return find.count() + " " + unit + (find.count() == 1 ? "" : "s")
			+ " · chunk " + (find.x() >> 4) + ", " + (find.z() >> 4);
	}

	/** Whether a remembered stash is still worth drawing, counting or reporting. */
	private boolean active(StashHistory.Stash stash) {
		return stash.state() != StashHistory.State.CLEARED
			&& stash.total() >= minCount.intValue()
			&& Math.hypot(stash.centerX(), stash.centerZ()) >= minDistance.get();
	}

	private List<String[]> hudLines() {
		Minecraft mc = mc();
		String dimension = mc.level == null ? "" : mc.level.dimension().identifier().toString();
		long count = stashes.values().stream().filter(stash -> stash.dimension().equals(dimension) && active(stash)).count();
		long s = (System.currentTimeMillis() - enabledAt) / 1000;
		String runtime = s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
		return List.of(ModuleHudElement.row("Stashes", count), ModuleHudElement.row("Runtime", runtime));
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
			for (StashHistory.Stash stash : stashes.values()) {
				if (!stash.dimension().equals(dimension) || !active(stash)) continue;
				double dx = stash.centerX() + 0.5 - cameraPos.x;
				double dz = stash.centerZ() + 0.5 - cameraPos.z;
				if (Math.hypot(dx, dz) > maxDistance.get()) continue;
				poseStack.pushPose();
				poseStack.translate(dx, bottom - cameraPos.y, dz);
				poseStack.scale(0.5f, height, 0.5f);
				WorldOverlays.cube(poseStack, WorldOverlays.Shape.Outline, true, argb, 0, 1f);
				poseStack.popPose();
			}
		}
		// Tracers at the player's height, so they read as a heading on the horizon.
		VertexConsumer tracers = null;
		if (stashTracers.get() && tracerStyle.get() == Tracers.Style.Smooth) {
			int tracerArgb = Colors.withAlpha(tracerColor.argb(), 0x99);
			double y = mc.player.getY();
			for (StashHistory.Stash stash : stashes.values()) {
				if (!stash.dimension().equals(dimension) || !active(stash)) continue;
				double x = stash.centerX() + 0.5, z = stash.centerZ() + 0.5;
				double distance = cameraPos.distanceTo(new Vec3(x, y, z));
				if (distance > maxDistance.get() || distance < hideCloser.get()) continue;
				if (tracers == null) tracers = EspRender.tracerBuffer();
				EspRender.tracer(tracers, x, y, z, tracerArgb, 1f, cameraPos);
			}
		}
		// The focused history row has its own white tracer, independent of the stash tracer settings.
		HistoryRow focus = focusedRow();
		if (focus != null && tracerStyle.get() == Tracers.Style.Smooth && dimension.equals(focus.dimension())) {
			if (tracers == null) tracers = EspRender.tracerBuffer();
			EspRender.tracer(tracers, focus.x() + 0.5, focus.y() + 0.5, focus.z() + 0.5, 0xCCFFFFFF, 1f, cameraPos);
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
		if (mc.level == null || mc.player == null) return;
		String dimension = mc.level.dimension().identifier().toString();
		if (stashTracers.get() && tracerStyle.get() == Tracers.Style.Classic) {
			int argb = Colors.withAlpha(tracerColor.argb(), 0x99);
			for (StashHistory.Stash stash : stashes.values()) {
				if (!stash.dimension().equals(dimension) || !active(stash)) continue;
				double x = stash.centerX() + 0.5, z = stash.centerZ() + 0.5;
				double distance = projector.distance(x, mc.player.getY(), z);
				if (distance > maxDistance.get() || distance < hideCloser.get()) continue;
				Tracers.pixel(ui, projector, x, mc.player.getY(), z, argb, 1);
			}
		}
		// Drawn last so the focused row's white line is never hidden by a stash tracer on top of it.
		if (tracerStyle.get() == Tracers.Style.Classic) {
			HistoryRow focus = focusedRow();
			if (focus != null && dimension.equals(focus.dimension())) {
				Tracers.pixel(ui, projector, focus.x() + 0.5, focus.y() + 0.5, focus.z() + 0.5, 0xCCFFFFFF, 1);
			}
		}
	}
}
