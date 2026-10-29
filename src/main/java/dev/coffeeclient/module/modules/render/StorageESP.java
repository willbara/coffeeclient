package dev.coffeeclient.module.modules.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BlockListSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.BlockIds;
import dev.coffeeclient.util.Nametag;
import dev.coffeeclient.util.Coords;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.Tracers;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.util.BlockSearch;
import dev.coffeeclient.util.WorldProjector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Highlights storage (chests, barrels, shulkers, storage minecarts, ...) near you, each kind with its
 * own toggle and colour, and can hide containers that likely came with world generation. Boxes
 * render in world space so they stay glued to the block; tracers and labels stay as HUD overlays.
 */
public final class StorageESP extends Module {
	/** Most blocks drawn at once from a block search (extra blocks only). */
	private static final int MAX_BLOCKS = 20_000;

	/** Kinds of storage, matched by block entity or entity class (as ChestESP groups). */
	public enum Group {
		CHEST("Chests", "Chest", 0xFFFFA000),
		TRAPPED("Trapped chests", "Trapped chest", 0xFFFF3333),
		BARREL("Barrels", "Barrel", 0xFFFFA000),
		SHULKER("Shulker boxes", "Shulker box", 0xFFFFA000),
		ENDER("Ender chests", "Ender chest", 0xFF7800FF),
		FURNACE("Furnaces", "Furnace", 0xFF8C8C8C),
		HOPPER("Hoppers", "Hopper", 0xFF6E6E6E),
		DISPENSER("Dispensers", "Dispenser", 0xFFA0A0A0),
		DROPPER("Droppers", "Dropper", 0xFFA0A0A0),
		CRAFTER("Crafters", "Crafter", 0xFFC87850),
		POT("Pots", "Pot", 0xFFB46E50),
		CHEST_CART("Chest minecarts", "Chest minecart", 0xFFFFA000),
		HOPPER_CART("Hopper minecarts", "Hopper minecart", 0xFF6E6E6E),
		CHEST_BOAT("Chest boats", "Chest boat", 0xFFFFA000);

		final String toggle, color;
		final int defaultColor;

		Group(String toggle, String color, int defaultColor) {
			this.toggle = toggle;
			this.color = color;
			this.defaultColor = defaultColor;
		}
	}

	public final NumberSetting radius = group(new NumberSetting("Radius", "Scan radius in blocks", 32, 8, 256, 8), "General");
	public final NumberSetting refresh = group(new NumberSetting("Refresh (ticks)", "How often to rescan", 20, 2, 60, 2), "General");
	public final BlockListSetting extra = group(new BlockListSetting("Extra blocks",
		"More blocks to highlight in the Other colour", List.of("minecraft:brewing_stand", "minecraft:chiseled_bookshelf")), "General");

	private final Map<Group, BooleanSetting> groupOn = new EnumMap<>(Group.class);
	private final Map<Group, ColorSetting> groupColor = new EnumMap<>(Group.class);

	{
		for (Group g : Group.values()) {
			BooleanSetting on = group(new BooleanSetting(g.toggle, "Highlight " + g.toggle.toLowerCase(Locale.ROOT), true), "Groups");
			groupOn.put(g, on);
			groupColor.put(g, group(new ColorSetting(g.color, g.toggle + " colour", g.defaultColor), "Groups").visibleWhen(on::get));
		}
	}

	public final ColorSetting other = group(new ColorSetting("Other", "Extra blocks colour", 0xFF8C8C8C), "Groups");

	public final BooleanSetting buried = group(new BooleanSetting("Highlight buried", "Own colour for chests with a block on top and shulkers that can't open", false), "Filters");
	public final ColorSetting buriedColor = group(new ColorSetting("Buried color", "Colour of buried storage", 0xFFFF55FF), "Filters");
	public final BooleanSetting useMinY = group(new BooleanSetting("Y limit", "Only show storage at or above a height", false), "Filters");
	public final NumberSetting minY = group(new NumberSetting("Min Y", "Lowest Y shown", 0, -64, 320, 1), "Filters");
	public final NumberSetting renderLimit = group(new NumberSetting("Render limit", "Most storage blocks drawn, nearest first", 2000, 50, 20000, 50), "Filters");
	public final BooleanSetting doubleOnly = group(new BooleanSetting("Double chests only", "Hide single chests (barrels and the rest stay)", false), "Filters");
	public final BooleanSetting shulkersOnly = group(new BooleanSetting("Shulkers only", "Only highlight shulker boxes; extra blocks are hidden too", false), "Filters");
	public final BooleanSetting shulkerAlerts = group(new BooleanSetting("Shulker chat alerts", "Chat line with the position of each shulker box found", false), "Filters");

	public final BooleanSetting hideSpawnerChests = group(new BooleanSetting("Hide spawner chests", "Hide single chests within 7 blocks of a mob spawner (dungeons)", false), "Natural loot");
	public final BooleanSetting hideTrialLoot = group(new BooleanSetting("Hide trial chamber loot", "Hide chests, barrels, hoppers and dispensers built into trial chambers", false), "Natural loot");
	public final BooleanSetting hideVillageChests = group(new BooleanSetting("Hide village chests", "Hide single chests near a door plus hay bales, glass panes or villagers", false), "Natural loot");

	public final EnumSetting<WorldOverlays.Shape> shape = group(new EnumSetting<>("Shape", "How each box is drawn", WorldOverlays.Shape.Outline), "Render");
	public final NumberSetting opacity = group(new NumberSetting("Fill opacity", "Alpha of filled faces", 80, 0, 255, 5), "Render");
	public final BooleanSetting seeThrough = group(new BooleanSetting("See through walls", "Render through terrain", true), "Render");
	public final NumberSetting width = group(new NumberSetting("Outline width", "Outline thickness", 1.0, 0.5, 5, 0.5), "Render");
	public final BooleanSetting merge = group(new BooleanSetting("Merge", "Draw touching blocks of one kind (double chests) as one box", true), "Render");
	public final BooleanSetting tracers = group(new BooleanSetting("Tracers", "Draw tracers to storage blocks", false), "Render");
	public final EnumSetting<Tracers.Style> tracerStyle = group(new EnumSetting<>("Tracer style", "Smooth: thin lines drawn in the world. Classic: Coffee's original pixel lines on the screen", Tracers.Style.Smooth), "Render");
	public final BooleanSetting nearestTracer = group(new BooleanSetting("Nearest tracer only", "Only the closest block and the closest minecart or boat get a tracer", false), "Render");
	public final BooleanSetting tracerFlash = group(new BooleanSetting("Tracer flash", "Pulse tracers with a smooth fade", false), "Render");
	public final BooleanSetting labels = group(new BooleanSetting("Labels", "Show a label above each storage block", true), "Render");
	public final BooleanSetting labelBackground = group(new BooleanSetting("Label background", "Draw a panel behind labels", true), "Render");
	public final NumberSetting scale = group(new NumberSetting("Label scale", "Label size", 1.0, 0.5, 2, 0.05), "Render");

	private record Found(Entity entity, int color) {
	}

	private EspRender.BlockSet found = new EspRender.BlockSet();
	private List<Found> foundEntities = List.of();
	/** Used for extra blocks without a block entity; null while not needed. */
	private BlockSearch.Handle search;
	private final NaturalLoot loot = new NaturalLoot();
	private final Set<BlockPos> alertedShulkers = new HashSet<>();
	private int ticks;

	public StorageESP() {
		super("StorageESP", Category.RENDER, "Highlights storage blocks near you.");
		opacity.visibleWhen(() -> shape.get() != WorldOverlays.Shape.Outline);
		width.visibleWhen(() -> shape.get() != WorldOverlays.Shape.Filled);
		tracerStyle.visibleWhen(tracers::get);
		nearestTracer.visibleWhen(tracers::get);
		tracerFlash.visibleWhen(tracers::get);
		for (BooleanSetting s : List.of(hideSpawnerChests, hideTrialLoot, hideVillageChests)) s.onChange(v -> loot.clear());
		buriedColor.visibleWhen(buried::get);
		minY.visibleWhen(useMinY::get);
		listen(Events.GameLeft.class, e -> alertedShulkers.clear());
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return extra;
	}

	/** Whether a storage group is highlighted now: its toggle, and Shulkers only. */
	private boolean shown(Group g) {
		return g != null && groupOn.get(g).get() && (!shulkersOnly.get() || g == Group.SHULKER);
	}

	@Override
	protected void onDisable() {
		found = new EspRender.BlockSet();
		foundEntities = List.of();
		loot.clear();
		alertedShulkers.clear();
		releaseSearch();
	}

	private void releaseSearch() {
		if (search != null) search.release();
		search = null;
	}

	@Override
	public void onTick() {
		Minecraft mc = mc();
		if (mc.level == null || mc.player == null || ++ticks % refresh.intValue() != 0) return;

		Set<Block> extras = shulkersOnly.get() ? Set.of() : extra.blocks();
		BlockPos origin = mc.player.blockPosition();
		int r = radius.intValue();
		int r2 = r * r;
		boolean filter = hideSpawnerChests.get() || hideTrialLoot.get() || hideVillageChests.get();
		if (filter) loot.beginScan(mc.level);
		record Hit(BlockPos pos, String label, int color) {
		}
		List<Hit> hits = new ArrayList<>();
		// Storage blocks are block entities, which each chunk already lists: far cheaper than a block search.
		for (int cx = (origin.getX() - r) >> 4; cx <= (origin.getX() + r) >> 4; cx++) {
			for (int cz = (origin.getZ() - r) >> 4; cz <= (origin.getZ() + r) >> 4; cz++) {
				LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
				if (chunk == null) continue;
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					BlockPos pos = be.getBlockPos();
					if (pos.distSqr(origin) > r2 || useMinY.get() && pos.getY() < minY.intValue()) continue;
					Block block = be.getBlockState().getBlock();
					Group g = groupOf(be);
					if (doubleOnly.get() && (g == Group.CHEST || g == Group.TRAPPED)
						&& be.getBlockState().hasProperty(ChestBlock.TYPE) && be.getBlockState().getValue(ChestBlock.TYPE) == ChestType.SINGLE) continue;
					int color;
					if (shown(g)) color = groupColor.get(g).argb();
					else if (extras.contains(block)) color = other.argb();
					else continue;
					if (filter && loot.hide(mc.level, be, hideSpawnerChests.get(), hideTrialLoot.get(), hideVillageChests.get())) continue;
					if (g == Group.SHULKER && shulkerAlerts.get() && alertedShulkers.add(pos.immutable())) {
						info("Shulker box at " + Coords.format(pos.getX()) + ", " + Coords.format(pos.getY()) + ", " + Coords.format(pos.getZ()));
					}
					String label = block.getName().getString();
					if (buried.get() && buried(mc.level, be)) {
						label += " (buried)";
						color = buriedColor.argb();
					}
					hits.add(new Hit(pos, label, color & 0xFFFFFF));
				}
			}
		}
		Set<Block> plain = new HashSet<>();
		for (Block b : extras) if (!(b instanceof EntityBlock)) plain.add(b);
		if (plain.isEmpty()) {
			releaseSearch();
		} else {
			if (search == null || !search.key().equals(plain)) {
				releaseSearch();
				Set<Block> key = Set.copyOf(plain);
				search = BlockSearch.acquire(key, state -> key.contains(state.getBlock()));
			}
			for (BlockPos pos : search.collect(origin, r, MAX_BLOCKS)) {
				if (useMinY.get() && pos.getY() < minY.intValue()) continue;
				hits.add(new Hit(pos, mc.level.getBlockState(pos).getBlock().getName().getString(), other.argb() & 0xFFFFFF));
			}
		}
		if (hits.size() > renderLimit.intValue()) {
			hits.sort(Comparator.comparingDouble(h -> h.pos().distSqr(origin)));
			hits = hits.subList(0, renderLimit.intValue());
		}
		Map<String, List<BlockPos>> byLabel = new LinkedHashMap<>();
		Map<String, Integer> colors = new LinkedHashMap<>();
		for (Hit hit : hits) {
			byLabel.computeIfAbsent(hit.label(), b -> new ArrayList<>()).add(hit.pos());
			colors.put(hit.label(), hit.color());
		}
		EspRender.BlockSet set = new EspRender.BlockSet();
		// Merging joins double chests (and rows of barrels) into one box with one label.
		for (Map.Entry<String, List<BlockPos>> entry : byLabel.entrySet()) {
			set.add(entry.getValue(), colors.get(entry.getKey()), entry.getKey(), merge.get());
		}
		found = set;

		List<Found> entities = new ArrayList<>();
		for (Entity e : mc.level.entitiesForRendering()) {
			Group g = groupOf(e);
			if (shown(g) && e.distanceToSqr(mc.player) <= r2) entities.add(new Found(e, groupColor.get(g).argb() & 0xFFFFFF));
		}
		foundEntities = entities;
	}

	/**
	 * ChestESP rule: a shulker whose lid side is blocked, or a chest with a block on top (another
	 * chest, shulker or barrel stacked on it doesn't count).
	 */
	public static boolean buried(ClientLevel level, BlockEntity be) {
		BlockPos pos = be.getBlockPos();
		var state = be.getBlockState();
		if (be instanceof ShulkerBoxBlockEntity && state.hasProperty(ShulkerBoxBlock.FACING)) {
			return !level.getBlockState(pos.relative(state.getValue(ShulkerBoxBlock.FACING))).isAir();
		}
		if (!(be instanceof ChestBlockEntity)) return false;
		var above = level.getBlockState(pos.above());
		if (above.isAir()) return false;
		var block = above.getBlock();
		return !(block instanceof ChestBlock || block instanceof ShulkerBoxBlock
			|| block instanceof BarrelBlock);
	}

	/** The storage group of a block entity, or null if it is none of them. */
	public static Group groupOf(BlockEntity be) {
		if (be instanceof TrappedChestBlockEntity) return Group.TRAPPED;
		if (be instanceof ChestBlockEntity) return Group.CHEST;
		if (be instanceof BarrelBlockEntity) return Group.BARREL;
		if (be instanceof ShulkerBoxBlockEntity) return Group.SHULKER;
		if (be instanceof EnderChestBlockEntity) return Group.ENDER;
		if (be instanceof AbstractFurnaceBlockEntity) return Group.FURNACE;
		if (be instanceof HopperBlockEntity) return Group.HOPPER;
		// A dropper is a dispenser subclass, so check it first.
		if (be instanceof DropperBlockEntity) return Group.DROPPER;
		if (be instanceof DispenserBlockEntity) return Group.DISPENSER;
		if (be instanceof CrafterBlockEntity) return Group.CRAFTER;
		if (be instanceof DecoratedPotBlockEntity) return Group.POT;
		return null;
	}

	private static Group groupOf(Entity e) {
		if (e instanceof MinecartChest) return Group.CHEST_CART;
		if (e instanceof MinecartHopper) return Group.HOPPER_CART;
		if (e instanceof AbstractChestBoat) return Group.CHEST_BOAT;
		return null;
	}

	@Override
	public String infoString() {
		int count = found.markers().size() + foundEntities.size();
		return count == 0 ? null : Integer.toString(count);
	}

	/** Boxes in world space, flushed after the level so water cannot paint over them. */
	@Override
	public void renderWorld(PoseStack poseStack, Vec3 cameraPos) {
		EspRender.BlockSet set = found;
		boolean smoothTracers = tracers.get() && tracerStyle.get() == Tracers.Style.Smooth;
		if (!set.isEmpty()) {
			set.render(shape.get(), seeThrough.get(), width.floatValue(), opacity.intValue(), cameraPos);
			if (smoothTracers) set.tracers(tracerAlpha(), 1f, cameraPos, nearestTracer.get());
		}
		List<Found> entities = foundEntities;
		if (entities.isEmpty()) return;
		float partialTick = mc().getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Frustum frustum = EspRender.frustum();
		List<AABB> boxes = new ArrayList<>(entities.size());
		int[] colors = new int[entities.size()];
		for (Found f : entities) {
			AABB box = EspRender.entityBox(f.entity(), partialTick);
			if (!frustum.isVisible(box)) continue;
			colors[boxes.size()] = f.color();
			boxes.add(box);
		}
		EspRender.boxes(boxes, colors, shape.get(), seeThrough.get(), width.floatValue(), opacity.intValue(), cameraPos);
		if (smoothTracers) {
			VertexConsumer lines = EspRender.tracerBuffer();
			for (Found f : Tracers.nearestOnly(entities, nearestTracer.get(), f -> f.entity().distanceToSqr(cameraPos))) {
				Vec3 p = f.entity().getPosition(partialTick);
				EspRender.tracer(lines, p.x, p.y + f.entity().getBbHeight() / 2, p.z, (tracerAlpha() << 24) | f.color(), 1f, cameraPos);
			}
		}
	}

	/** Tracer alpha, pulsing when Tracer flash is on. */
	private int tracerAlpha() {
		return tracerFlash.get() ? Tracers.flash(0x80) : 0x80;
	}

	@Override
	public void renderOverlay(Ui ui, WorldProjector projector) {
		boolean classic = tracers.get() && tracerStyle.get() == Tracers.Style.Classic;
		if (classic) found.screenTracers(ui, projector, tracerAlpha(), 1f, nearestTracer.get());
		float partialTick = mc().getDeltaTracker().getGameTimeDeltaPartialTick(false);
		if (classic) {
			for (Found f : Tracers.nearestOnly(foundEntities, nearestTracer.get(), f -> f.entity().distanceToSqr(projector.camera()))) {
				Vec3 p = f.entity().getPosition(partialTick);
				Tracers.pixel(ui, projector, p.x, p.y + f.entity().getBbHeight() / 2, p.z, (tracerAlpha() << 24) | f.color(), 1);
			}
		}
		if (!labels.get()) return;
		for (EspRender.BlockSet.Marker m : found.markers()) label(ui, projector, m.center().x, m.top() + 0.1, m.center().z, m.label(), m.color());
		for (Found f : foundEntities) {
			Vec3 p = f.entity().getPosition(partialTick);
			label(ui, projector, p.x, p.y + f.entity().getBbHeight() + 0.1, p.z, f.entity().getName().getString(), f.color());
		}
	}

	private void label(Ui ui, WorldProjector projector, double x, double y, double z, String text, int color) {
		float[] point = projector.project(x, y, z);
		if (!projector.onScreen(point, 30)) return;
		Nametag.draw(ui, point[0], point[1], List.of(new Nametag.Line(text, 0xFF000000 | color)),
			scale.floatValue(), labelBackground.get(), -1, 0, null);
	}
}
