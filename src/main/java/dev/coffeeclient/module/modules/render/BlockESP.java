package dev.coffeeclient.module.modules.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.mixin.BeaconBlockEntityAccessor;
import dev.coffeeclient.render.OverlayRenderer;
import dev.coffeeclient.render.SignMarks;
import dev.coffeeclient.render.SpawnerMarks;
import dev.coffeeclient.render.Tracers;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.BlockListSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorMapSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.setting.StringSetting;
import dev.coffeeclient.util.BlockPresets;
import dev.coffeeclient.util.BlockSearch;
import dev.coffeeclient.util.Nametag;
import dev.coffeeclient.util.WorldProjector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.ShelfBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Renders configured blocks through walls, with optional labels and tracers. Matches come from a
 * shared {@link BlockSearch}, so chunks are searched once when they load and then follow block
 * updates instead of being rescanned. Also labels signs through walls (grouped, nearest first,
 * optionally printed to chat once per text, with a guess at old signs and notes when one changes
 * or disappears) and draws beacon effect ranges.
 */
public final class BlockESP extends Module {
	private static final long ANTI_ESP_COOLDOWN_MS = 30_000;

	public final BlockListSetting blocks = group(new BlockListSetting("Blocks", "Blocks to highlight",
		List.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore", "minecraft:ancient_debris")), "General");
	public final StringSetting query = group(new StringSetting("Query",
		"Also match every block whose id or name contains one of these comma-separated words (\"ore, copper\")", "", 200), "General");
	public final NumberSetting radius = group(new NumberSetting("Radius", "Scan radius in blocks", 48, 8, 256, 8), "General");
	public final NumberSetting refresh = group(new NumberSetting("Refresh (ticks)", "How often to pick up new matches", 20, 2, 60, 2), "General");
	public final NumberSetting limit = group(new NumberSetting("Result limit", "Most blocks drawn at once; the nearest are kept", 20000, 100, 20000, 100), "General");
	public final BooleanSetting useMinY = group(new BooleanSetting("Y limit", "Only show blocks at or above a height", false), "General");
	public final NumberSetting minY = group(new NumberSetting("Min Y", "Lowest Y shown", 0, -64, 320, 1), "General");
	public final BooleanSetting searchItems = group(new BooleanSetting("Search item contents", "Also highlight dropped items, item frames and shelves holding an item that matches the Query", false), "General");
	public final BooleanSetting antiEsp = group(new BooleanSetting("Anti-ESP detection", "Chat warning when searched blocks have no block entity, a sign the server fakes them", false), "General");

	public final EnumSetting<WorldOverlays.Shape> shape = group(new EnumSetting<>("Shape", "How each box is drawn", WorldOverlays.Shape.Outline), "Render");
	public final BooleanSetting corners = group(new BooleanSetting("Highlight corners", "Draw short stubs at each block's corners instead of full edges", false), "Render");
	public final ColorSetting color = group(new ColorSetting("Color", "Box colour", 0xFF00FFC8), "Render");
	public final ColorMapSetting blockColors = group(new ColorMapSetting("Block colours", "A colour for each listed block (others use Color)",
		blocks::get, BlockESP::blockName, color::argb), "Render");
	public final NumberSetting opacity = group(new NumberSetting("Fill opacity", "Alpha of filled faces", 80, 0, 255, 5), "Render");
	public final BooleanSetting seeThrough = group(new BooleanSetting("See through walls", "Render through terrain", true), "Render");
	public final NumberSetting width = group(new NumberSetting("Outline width", "Outline thickness", 1.0, 0.5, 5, 0.5), "Render");
	public final BooleanSetting merge = group(new BooleanSetting("Merge veins", "Draw touching blocks of one kind as one shape, with one tracer and label", true), "Render");
	public final BooleanSetting tracers = group(new BooleanSetting("Tracers", "Draw tracers to matching blocks", false), "Render");
	public final EnumSetting<Tracers.Style> tracerStyle = group(new EnumSetting<>("Tracer style", "Smooth: thin lines drawn in the world. Classic: Coffee's original pixel lines on the screen", Tracers.Style.Smooth), "Render");
	public final BooleanSetting labels = group(new BooleanSetting("Labels", "Show a label above each block or vein", false), "Render");
	public final BooleanSetting labelBackground = group(new BooleanSetting("Label background", "Draw a panel behind labels", true), "Render");
	public final NumberSetting scale = group(new NumberSetting("Label scale", "Label size", 1.0, 0.5, 2, 0.05), "Render");

	public final BooleanSetting spawnerInfo = group(new BooleanSetting("Spawner info", "Outlines mob spawners and labels them with their mob and distance", false), "Spawners");
	public final BooleanSetting trialInfo = group(new BooleanSetting("Trial spawner info", "Outlines trial spawners and labels them with mob, state, ominous and a guessed cooldown", false), "Spawners");
	public final BooleanSetting activationRange = group(new BooleanSetting("Activation range", "Draws the range a spawner wakes up in, green while you are inside", false), "Spawners");
	public final ColorSetting spawnerColor = group(new ColorSetting("Spawner color", "Mob spawner outline and label", 0xFFFFD84D)
		.visibleWhen(spawnerInfo::get), "Spawners");
	public final ColorSetting trialColor = group(new ColorSetting("Trial spawner color", "Trial spawner outline and label", 0xFF66B3FF)
		.visibleWhen(trialInfo::get), "Spawners");
	public final ColorSetting insideColor = group(new ColorSetting("Inside color", "Activation range colour while you are inside it", 0xFF55FF55)
		.visibleWhen(activationRange::get), "Spawners");

	public final BooleanSetting beaconRange = group(new BooleanSetting("Beacon range", "Draws the square a beacon's effects reach, green while you are inside", false), "Spawners");
	public final ColorSetting beaconColor = group(new ColorSetting("Beacon color", "Beacon range outline", 0xFFFFE066)
		.visibleWhen(beaconRange::get), "Spawners");

	public final BooleanSetting signLabels = group(new BooleanSetting("Sign labels", "Show what signs say through walls", false), "Signs");
	public final NumberSetting maxSigns = group(new NumberSetting("Max signs", "Nearest sign labels shown", 12, 1, 100, 1), "Signs");
	public final NumberSetting signCluster = group(new NumberSetting("Group within", "Signs this close share one label (0 = off)", 3, 0, 16, 1), "Signs");
	public final ColorSetting signColor = group(new ColorSetting("Sign color", "Sign label colour", 0xFFFFFFFF), "Signs");
	public final BooleanSetting oldSigns = group(new BooleanSetting("Mark maybe-old", "Colour signs that look like they predate 1.8 (oak, plain, one-sided, no recent year)", false), "Signs");
	public final ColorSetting oldColor = group(new ColorSetting("Old sign color", "Colour of maybe-old signs", 0xFFFFAA00), "Signs");
	public final BooleanSetting signChat = group(new BooleanSetting("Print to chat", "Print each sign's text to chat once", false), "Signs");
	public final BooleanSetting signHistory = group(new BooleanSetting("Report changes", "Say in chat when a sign you saw changes or disappears", false), "Signs");

	private final SignMarks signMarks = new SignMarks();
	private List<SignMarks.Mark> signs = List.of();
	private final Set<String> printedSigns = new HashSet<>();
	private List<BeaconBlockEntity> beacons = List.of();
	private final SpawnerMarks spawnerMarks = new SpawnerMarks();
	private List<SpawnerMarks.Mark> spawners = List.of();
	private BlockSearch.Handle search;
	private EspRender.BlockSet found = new EspRender.BlockSet();
	private long builtVersion = -1;
	private BlockPos builtOrigin;
	private int builtRadius;
	private boolean builtMerge;
	private int builtColor;
	private int builtFilters;
	private long antiEspWarnedAt;
	private int ticks;
	/** The list plus the query's matches, rebuilt only when either changes. */
	private Set<Block> wanted = Set.of();
	private Set<Block> wantedList;
	private String wantedQuery;

	public BlockESP() {
		super("BlockESP", List.of("Block ESP", "Search"), Category.RENDER, "Highlights configured blocks through walls.");
		opacity.visibleWhen(() -> shape.get() != WorldOverlays.Shape.Outline);
		width.visibleWhen(() -> shape.get() != WorldOverlays.Shape.Filled);
		labelBackground.visibleWhen(labels::get);
		scale.visibleWhen(labels::get);
		tracerStyle.visibleWhen(tracers::get);
		minY.visibleWhen(useMinY::get);
		activationRange.visibleWhen(() -> spawnerInfo.get() || trialInfo.get());
		for (Setting<?> s : List.of(maxSigns, signCluster, signColor, oldSigns, signChat, signHistory)) s.visibleWhen(signLabels::get);
		oldColor.visibleWhen(() -> signLabels.get() && oldSigns.get());
		blocks.presets(BlockPresets.all());
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return blocks;
	}

	@Override
	protected void onDisable() {
		clearBlocks();
		spawners = List.of();
		spawnerMarks.clear();
		signs = List.of();
		signMarks.clear();
		printedSigns.clear();
		beacons = List.of();
	}

	private void clearBlocks() {
		if (search != null) search.release();
		search = null;
		found = new EspRender.BlockSet();
		builtVersion = -1;
	}

	@Override
	public void onTick() {
		Minecraft mc = mc();
		if (mc.level == null || mc.player == null || ++ticks % refresh.intValue() != 0) return;

		spawners = spawnerInfo.get() || trialInfo.get()
			? spawnerMarks.scan(mc.level, mc.player.blockPosition(), radius.intValue(), spawnerInfo.get(), trialInfo.get())
			: List.of();
		scanSignsAndBeacons(mc.level, mc.player.blockPosition());

		Set<Block> wanted = wanted();
		boolean items = searchItems.get();
		if (wanted.isEmpty() && !items) {
			clearBlocks();
			return;
		}
		if (wanted.isEmpty()) {
			// Item search only: no block matches to follow.
			if (search != null) search.release();
			search = null;
		} else if (search == null || !search.key().equals(wanted)) {
			if (search != null) search.release();
			Set<Block> key = wanted;
			search = BlockSearch.acquire(key, state -> key.contains(state.getBlock()));
			builtVersion = -1;
		}

		// Rebuild only when the matches or these filters changed, or the player moved enough to change what is in range.
		// Item search also rebuilds every refresh: items move and frames change without any block update.
		BlockPos origin = mc.player.blockPosition();
		long version = search == null ? -2 : search.version();
		int rgb = color.argb() & 0xFFFFFF;
		int filters = Objects.hash(useMinY.get(), minY.intValue(), limit.intValue(), items, antiEsp.get(), query.get(), blockColors.get());
		if (!items && version == builtVersion && radius.intValue() == builtRadius && merge.get() == builtMerge && rgb == builtColor && filters == builtFilters
			&& builtOrigin != null && builtOrigin.distManhattan(origin) < 4) return;
		builtVersion = version;
		builtOrigin = origin;
		builtRadius = radius.intValue();
		builtMerge = merge.get();
		builtColor = rgb;
		builtFilters = filters;

		List<BlockPos> matches = search == null ? new ArrayList<>() : new ArrayList<>(search.collect(origin, radius.intValue(), Integer.MAX_VALUE));
		matches.removeIf(p -> useMinY.get() && p.getY() < minY.intValue());
		if (antiEsp.get()) checkAntiEsp(mc.level, matches);
		if (matches.size() > limit.intValue()) {
			matches.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(origin)));
			matches = matches.subList(0, limit.intValue());
		}
		Map<Block, List<BlockPos>> byBlock = new LinkedHashMap<>();
		for (BlockPos pos : matches) {
			byBlock.computeIfAbsent(mc.level.getBlockState(pos).getBlock(), b -> new ArrayList<>()).add(pos);
		}
		EspRender.BlockSet set = new EspRender.BlockSet();
		for (Map.Entry<Block, List<BlockPos>> entry : byBlock.entrySet()) {
			int blockRgb = blockColors.color(BuiltInRegistries.BLOCK.getKey(entry.getKey()).toString()) & 0xFFFFFF;
			set.add(entry.getValue(), blockRgb, entry.getKey().getName().getString(), merge.get());
		}
		if (items) set.add(itemMatches(mc.level, origin), rgb, "Item", merge.get());
		found = set;
	}

	private void scanSignsAndBeacons(ClientLevel level, BlockPos origin) {
		if (!signLabels.get() && !beaconRange.get()) {
			signs = List.of();
			beacons = List.of();
			return;
		}
		int r = radius.intValue();
		long r2 = (long) r * r;
		List<SignBlockEntity> foundSigns = new ArrayList<>();
		List<BeaconBlockEntity> foundBeacons = new ArrayList<>();
		for (int cx = (origin.getX() - r) >> 4; cx <= (origin.getX() + r) >> 4; cx++) {
			for (int cz = (origin.getZ() - r) >> 4; cz <= (origin.getZ() + r) >> 4; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
				if (chunk == null) continue;
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					if (be.getBlockPos().distSqr(origin) > r2) continue;
					if (be instanceof SignBlockEntity sign) foundSigns.add(sign);
					else if (be instanceof BeaconBlockEntity beacon) foundBeacons.add(beacon);
				}
			}
		}
		beacons = beaconRange.get() ? foundBeacons : List.of();
		if (!signLabels.get()) {
			signs = List.of();
			return;
		}
		// Only report signs that should still be visible: in range and in a loaded chunk.
		java.util.function.Predicate<BlockPos> watched = p -> p.distSqr(origin) <= r2 && level.hasChunk(p.getX() >> 4, p.getZ() >> 4);
		signs = signMarks.update(foundSigns, watched,
			(pos, change) -> { if (signHistory.get()) info("Sign at " + pos.toShortString() + " changed: " + change); },
			(pos, was) -> { if (signHistory.get()) info("Sign at " + pos.toShortString() + " is gone (was: " + was + ")"); });
		if (signChat.get()) {
			for (SignMarks.Mark m : signs) if (printedSigns.add(m.text())) info(m.pos().toShortString() + ": " + m.text());
		}
	}

	/** Positions of dropped items, item frames and shelves holding an item that matches the Query. */
	private List<BlockPos> itemMatches(ClientLevel level, BlockPos origin) {
		List<BlockPos> out = new ArrayList<>();
		String q = query.get();
		if (q.isBlank()) return out;
		int r = radius.intValue(), r2 = r * r;
		for (Entity e : level.entitiesForRendering()) {
			ItemStack stack = e instanceof ItemEntity item ? item.getItem() : e instanceof ItemFrame frame ? frame.getItem() : ItemStack.EMPTY;
			if (!stack.isEmpty() && e.blockPosition().distSqr(origin) <= r2 && matchesItem(stack, q)) out.add(e.blockPosition());
		}
		for (int cx = (origin.getX() - r) >> 4; cx <= (origin.getX() + r) >> 4; cx++) {
			for (int cz = (origin.getZ() - r) >> 4; cz <= (origin.getZ() + r) >> 4; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
				if (chunk == null) continue;
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					if (!(be instanceof ShelfBlockEntity shelf) || be.getBlockPos().distSqr(origin) > r2) continue;
					for (ItemStack stack : shelf.getItems()) {
						if (!stack.isEmpty() && matchesItem(stack, q)) {
							out.add(be.getBlockPos());
							break;
						}
					}
				}
			}
		}
		out.removeIf(p -> useMinY.get() && p.getY() < minY.intValue());
		return out;
	}

	/** Anti-ESP: a searched block that should have a block entity but has none, the usual fake-chest trick. */
	private void checkAntiEsp(ClientLevel level, List<BlockPos> matches) {
		// shortcut: checks only the missing block entity, not delayed reveals or packet bursts, upgrade if false alarms show up
		int fake = 0;
		for (BlockPos pos : matches) {
			if (level.getBlockState(pos).hasBlockEntity() && level.getBlockEntity(pos) == null) fake++;
		}
		long now = System.currentTimeMillis();
		if (fake > 0 && now - antiEspWarnedAt > ANTI_ESP_COOLDOWN_MS) {
			antiEspWarnedAt = now;
			info("Possible anti-ESP: " + fake + " searched block(s) have no block entity");
		}
	}

	private Set<Block> wanted() {
		Set<Block> list = blocks.blocks();
		String q = query.get();
		if (!list.equals(wantedList) || !q.equals(wantedQuery)) {
			wantedList = list;
			wantedQuery = q;
			Set<Block> all = new HashSet<>(list);
			if (!q.isBlank()) for (Block b : BuiltInRegistries.BLOCK) if (matchesQuery(b, q)) all.add(b);
			wanted = Set.copyOf(all);
		}
		return wanted;
	}

	private static String blockName(String id) {
		Identifier key = Identifier.tryParse(id);
		return key == null ? id : BuiltInRegistries.BLOCK.getValue(key).getName().getString();
	}

	/**
	 * True when any comma-separated term is part of the block's id, its path (with or without
	 * underscores) or its display name, ignoring case. As Search query.
	 */
	public static boolean matchesQuery(Block block, String query) {
		return matchesTerms(query, BuiltInRegistries.BLOCK.getKey(block).toString(), block.getName().getString());
	}

	/** As {@link #matchesQuery}, for an item stack: its id or display name. */
	public static boolean matchesItem(ItemStack stack, String query) {
		return matchesTerms(query, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getHoverName().getString());
	}

	/** True when any comma-separated term is in the id ("ns:path"), its path (with or without underscores) or the name. */
	static boolean matchesTerms(String query, String fullId, String displayName) {
		String path = fullId.substring(fullId.indexOf(':') + 1), spaced = path.replace('_', ' ');
		String name = displayName.toLowerCase(Locale.ROOT);
		for (String term : query.toLowerCase(Locale.ROOT).split(",")) {
			term = term.trim();
			if (term.isEmpty()) continue;
			if (fullId.contains(term) || path.contains(term) || spaced.contains(term) || name.contains(term)) return true;
		}
		return false;
	}

	@Override
	public String infoString() {
		return found.isEmpty() ? null : Integer.toString(found.size());
	}

	@Override
	public void renderWorld(PoseStack poseStack, Vec3 cameraPos) {
		renderSpawners(cameraPos);
		renderBeacons(cameraPos);
		EspRender.BlockSet set = found;
		if (set.isEmpty()) return;
		if (corners.get()) set.corners(seeThrough.get(), width.floatValue(), cameraPos);
		else set.render(shape.get(), seeThrough.get(), width.floatValue(), opacity.intValue(), cameraPos);
		if (tracers.get() && tracerStyle.get() == Tracers.Style.Smooth) set.tracers(0x80, 1f, cameraPos);
	}

	private int markColor(SpawnerMarks.Mark mark) {
		return 0xFF000000 | ((mark.trial() ? trialColor : spawnerColor).argb() & 0xFFFFFF);
	}

	private void renderSpawners(Vec3 cam) {
		if (spawners.isEmpty() || mc().player == null) return;
		List<AABB> boxes = new ArrayList<>();
		int[] colors = new int[spawners.size()];
		for (int i = 0; i < spawners.size(); i++) {
			boxes.add(new AABB(spawners.get(i).pos()));
			colors[i] = markColor(spawners.get(i)) & 0xFFFFFF;
		}
		EspRender.boxes(boxes, colors, shape.get(), seeThrough.get(), width.floatValue(), opacity.intValue(), cam);
		if (!activationRange.get()) return;
		VertexConsumer lines = OverlayRenderer.buffer(WorldOverlays.outlineType(seeThrough.get()));
		Vec3 player = mc().player.position();
		for (SpawnerMarks.Mark mark : spawners) {
			Vec3 center = Vec3.atCenterOf(mark.pos());
			boolean inside = player.distanceToSqr(center) <= (double) mark.range() * mark.range();
			int argb = inside ? 0xFF000000 | (insideColor.argb() & 0xFFFFFF) : markColor(mark);
			float x = (float) (center.x - cam.x), y = (float) (center.y - cam.y), z = (float) (center.z - cam.z);
			for (int ring = 0; ring < 3; ring++) ring(lines, x, y, z, mark.range(), ring, argb, width.floatValue());
		}
	}

	/** Each beacon's effect square (10 blocks per pyramid level, plus 10) at the beacon's height. */
	private void renderBeacons(Vec3 cam) {
		if (beacons.isEmpty() || mc().player == null) return;
		VertexConsumer lines = OverlayRenderer.buffer(WorldOverlays.outlineType(seeThrough.get()));
		Vec3 player = mc().player.position();
		for (BeaconBlockEntity beacon : beacons) {
			int levels = ((BeaconBlockEntityAccessor) beacon).coffeeclient$levels();
			if (levels <= 0) continue;
			int range = levels * 10 + 10;
			BlockPos p = beacon.getBlockPos();
			boolean inside = Math.abs(player.x - p.getX() - 0.5) <= range + 0.5 && Math.abs(player.z - p.getZ() - 0.5) <= range + 0.5 && player.y >= p.getY() - range;
			int argb = 0xFF000000 | ((inside ? insideColor : beaconColor).argb() & 0xFFFFFF);
			float x0 = (float) (p.getX() - range - cam.x), x1 = (float) (p.getX() + range + 1 - cam.x);
			float z0 = (float) (p.getZ() - range - cam.z), z1 = (float) (p.getZ() + range + 1 - cam.z);
			float y = (float) (p.getY() + 0.05 - cam.y);
			float w = width.floatValue();
			WorldOverlays.line(lines, x0, y, z0, x1, y, z0, 1, 0, 0, argb, w);
			WorldOverlays.line(lines, x1, y, z0, x1, y, z1, 0, 0, 1, argb, w);
			WorldOverlays.line(lines, x1, y, z1, x0, y, z1, -1, 0, 0, argb, w);
			WorldOverlays.line(lines, x0, y, z1, x0, y, z0, 0, 0, -1, argb, w);
		}
	}

	private static final int RING_SEGMENTS = 48;
	private static final float[] COS = new float[RING_SEGMENTS + 1], SIN = new float[RING_SEGMENTS + 1];

	static {
		for (int i = 0; i <= RING_SEGMENTS; i++) {
			COS[i] = (float) Math.cos(Math.PI * 2 * i / RING_SEGMENTS);
			SIN[i] = (float) Math.sin(Math.PI * 2 * i / RING_SEGMENTS);
		}
	}

	/** One circle of the activation sphere: ring 0 flat, rings 1 and 2 upright. */
	private static void ring(VertexConsumer vc, float cx, float cy, float cz, float r, int ring, int argb, float width) {
		for (int i = 0; i < RING_SEGMENTS; i++) {
			float c0 = COS[i] * r, s0 = SIN[i] * r, c1 = COS[i + 1] * r, s1 = SIN[i + 1] * r;
			float dc = c1 - c0, ds = s1 - s0, len = (float) Math.sqrt(dc * dc + ds * ds);
			dc /= len;
			ds /= len;
			switch (ring) {
				case 0 -> WorldOverlays.line(vc, cx + c0, cy, cz + s0, cx + c1, cy, cz + s1, dc, 0, ds, argb, width);
				case 1 -> WorldOverlays.line(vc, cx + c0, cy + s0, cz, cx + c1, cy + s1, cz, dc, ds, 0, argb, width);
				default -> WorldOverlays.line(vc, cx, cy + s0, cz + c0, cx, cy + s1, cz + c1, 0, ds, dc, argb, width);
			}
		}
	}

	@Override
	public void renderOverlay(Ui ui, WorldProjector projector) {
		for (SpawnerMarks.Mark mark : spawners) {
			Vec3 center = Vec3.atCenterOf(mark.pos());
			float[] point = projector.project(center.x, mark.pos().getY() + 1.1, center.z);
			if (!projector.onScreen(point, 30) || mc().player == null) continue;
			String[] text = mark.text().split("\n");
			text[0] += " [" + Math.round(mc().player.position().distanceTo(center)) + "]";
			List<Nametag.Line> lines = new ArrayList<>();
			for (String t : text) lines.add(new Nametag.Line(t, markColor(mark)));
			Nametag.draw(ui, point[0], point[1], lines, scale.floatValue(), labelBackground.get(), ui.theme.panel, -1, 0, null);
		}
		renderSignLabels(ui, projector);
		if (tracers.get() && tracerStyle.get() == Tracers.Style.Classic) found.screenTracers(ui, projector, 0x80, 1f);
		if (!labels.get()) return;
		for (EspRender.BlockSet.Marker m : found.markers()) {
			int argb = 0xFF000000 | m.color();
			float[] point = projector.project(m.center().x, m.top() + 0.1, m.center().z);
			if (!projector.onScreen(point, 30)) continue;
			String text = m.count() > 1 ? m.label() + " x" + m.count() : m.label();
			Nametag.draw(ui, point[0], point[1], List.of(new Nametag.Line(text, argb)),
				scale.floatValue(), labelBackground.get(), ui.theme.panel, -1, 0, null);
		}
	}

	/** Nearest signs first; signs within Group within of a labelled one join its label. */
	private void renderSignLabels(Ui ui, WorldProjector projector) {
		if (!signLabels.get() || signs.isEmpty()) return;
		Vec3 cam = projector.camera();
		List<SignMarks.Mark> sorted = new ArrayList<>(signs);
		sorted.sort(java.util.Comparator.comparingDouble(m -> Vec3.atCenterOf(m.pos()).distanceToSqr(cam)));
		List<SignMarks.Mark> heads = new ArrayList<>();
		List<Integer> extra = new ArrayList<>();
		double group = signCluster.get();
		outer:
		for (SignMarks.Mark m : sorted) {
			if (group > 0) {
				for (int i = 0; i < heads.size(); i++) {
					if (heads.get(i).pos().distSqr(m.pos()) <= group * group) {
						extra.set(i, extra.get(i) + 1);
						continue outer;
					}
				}
			}
			if (heads.size() >= maxSigns.intValue()) continue;
			heads.add(m);
			extra.add(0);
		}
		for (int i = 0; i < heads.size(); i++) {
			SignMarks.Mark m = heads.get(i);
			Vec3 c = Vec3.atCenterOf(m.pos());
			float[] point = projector.project(c.x, c.y + 0.6, c.z);
			if (!projector.onScreen(point, 30)) continue;
			int argb = 0xFF000000 | ((oldSigns.get() && m.maybeOld() ? oldColor : signColor).argb() & 0xFFFFFF);
			String text = extra.get(i) > 0 ? m.text() + "  (+" + extra.get(i) + ")" : m.text();
			Nametag.draw(ui, point[0], point[1], List.of(new Nametag.Line(text, argb)), scale.floatValue(), labelBackground.get(), ui.theme.panel, -1, 0, null);
		}
	}
}
