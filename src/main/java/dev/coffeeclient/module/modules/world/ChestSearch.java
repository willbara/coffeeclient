package dev.coffeeclient.module.modules.world;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.ModuleHudElement;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.Tracers;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.ActionSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.setting.StringSetting;
import dev.coffeeclient.util.ItemIds;
import dev.coffeeclient.util.Nametag;
import dev.coffeeclient.util.WorldProjector;
import dev.coffeeclient.util.Coords;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoubleBlockCombiner;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Remembers what was in every container you open, per server, and finds items in them later:
 * matching containers are highlighted in the world and listed on the HUD, and looking at a known
 * container shows what it held. No waypoints and no
 * loot-table checks.
 *
 * <p>The opened container's position comes from the block you right-clicked just before the screen
 * opened; its contents are read from the open screen every tick and stored when it closes. Items
 * inside shulker boxes and bundles are searched too.
 */
public final class ChestSearch extends Module {
	/** A right-click this old no longer explains a container screen opening. */
	private static final long CLICK_WINDOW_MS = 3000;

	public final StringSetting search = group(new StringSetting("Search", "Item to find (id or name, part of it is enough)", "", 64), "Search");
	public final NumberSetting maxResults = group(new NumberSetting("Max results", "Most containers listed and highlighted", 50, 5, 500, 5), "Search");
	public final NumberSetting range = group(new NumberSetting("Range", "Only highlight containers this close (0 = any loaded)", 0, 0, 1024, 16)
		.format(v -> v < 1 ? "Any" : (int) Math.round(v) + " m"), "Search");
	public final BooleanSetting showList = group(new BooleanSetting("Results panel", "List the matches on the HUD", true), "Search");

	public final BooleanSetting highlight = group(new BooleanSetting("Highlight matches", "Box containers holding the item", true), "Render");
	public final ColorSetting matchColor = group(new ColorSetting("Match color", "Colour of matching containers", 0xFF22FF88), "Render");
	public final BooleanSetting markOpened = group(new BooleanSetting("Mark recorded", "Also box every container already recorded", false), "Render");
	public final ColorSetting openedColor = group(new ColorSetting("Recorded color", "Colour of recorded containers", 0xFFFF9900), "Render");
	public final EnumSetting<WorldOverlays.Shape> shape = group(new EnumSetting<>("Shape", "How boxes are drawn", WorldOverlays.Shape.Both), "Render");
	public final NumberSetting opacity = group(new NumberSetting("Fill opacity", "Alpha of filled faces", 50, 0, 255, 5), "Render");
	public final BooleanSetting seeThrough = group(new BooleanSetting("See through walls", "Draw boxes through terrain", true), "Render");
	public final BooleanSetting tracers = group(new BooleanSetting("Tracers", "Lines to matching containers", false), "Render");
	public final EnumSetting<Tracers.Style> tracerStyle = group(new EnumSetting<>("Tracer style", "Smooth: thin lines drawn in the world. Classic: Coffee's original pixel lines on the screen", Tracers.Style.Smooth), "Render");
	public final BooleanSetting labels = group(new BooleanSetting("Labels", "Matching count above each container", true), "Render");
	public final BooleanSetting lookPreview = group(new BooleanSetting("Look preview", "Show a known container's contents while you look at it", true), "Render");

	public final BooleanSetting chatNotice = group(new BooleanSetting("Chat notice", "Say in chat when a container is recorded", false), "Recording");
	public final BooleanSetting forgetMissing = group(new BooleanSetting("Forget missing", "Drop containers that are gone when you come back", true), "Recording");
	public final ActionSetting clear = group(new ActionSetting("Forget this server", "Delete everything recorded on the current server", () -> {
		if (this.db != null) this.db.clear();
		rebuild();
	}), "Recording");

	private ChestDatabase db;
	private BlockPos clicked;
	private long clickedAt;
	/** The container screen being watched and where it came from (null pos = ender chest). */
	private AbstractContainerScreen<?> watching;
	private BlockPos watchingPos, watchingPartner;
	private String watchingBlock;
	private List<ChestDatabase.Item> watchingItems;

	private record Match(ChestDatabase.Entry entry, int count, double distance) {
	}

	private List<Match> matches = List.of();
	private EspRender.BlockSet boxes = new EspRender.BlockSet();
	private int ticks;

	private final ModuleHudElement panel = new ModuleHudElement(this, "chest_search", "Chest search", this::hudLines,
		(w, h) -> new int[] {2, h / 3});

	public ChestSearch() {
		super("ChestSearch", List.of("Chest Search", "Item finder"), Category.WORLD, "Remembers what you saw in containers and finds items in them.");
		panel.when(() -> showList.get() && !search.get().isBlank());
		matchColor.visibleWhen(highlight::get);
		openedColor.visibleWhen(markOpened::get);
		tracerStyle.visibleWhen(tracers::get);
		search.onChange(v -> rebuild());
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public List<HudElement> hudElements() {
		return List.of(panel);
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.InteractBlock.class, EventBus.LOWEST, e -> {
			if (e.isCancelled()) return;
			clicked = e.hit.getBlockPos().immutable();
			clickedAt = System.currentTimeMillis();
		});
		listen(Events.GameLeft.class, e -> {
			stopWatching();
			if (db != null) db.save();
			db = null;
		});
		// Closing the game: write recent chests now (the shutdown flush waits for it), on or off.
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
			if (db != null) db.save();
		});
	}

	@Override
	protected void onDisable() {
		stopWatching();
		matches = List.of();
		boxes = new EspRender.BlockSet();
	}

	// ---- recording ----

	@Override
	public void onTick() {
		Minecraft mc = mc();
		if (mc.level == null || mc.player == null) return;
		String server = serverKey(mc);
		if (db == null || !db.server().equals(server)) {
			if (db != null) db.save();
			db = ChestDatabase.load(server);
			rebuild();
		}

		if (mc.gui.screen() instanceof AbstractContainerScreen<?> screen && !(screen instanceof InventoryScreen)
			&& !(screen instanceof CreativeModeInventoryScreen)) {
			if (screen != watching) startWatching(screen);
			if (watching != null) watchingItems = read(screen);
		} else {
			stopWatching();
		}

		if (++ticks % 20 == 0) {
			if (forgetMissing.get()) forgetMissing();
			rebuild();
		}
	}

	private void startWatching(AbstractContainerScreen<?> screen) {
		stopWatching();
		Minecraft mc = mc();
		if (clicked == null || System.currentTimeMillis() - clickedAt > CLICK_WINDOW_MS) return;
		BlockEntity be = mc.level.getBlockEntity(clicked);
		if (!(be instanceof Container) && !(be instanceof EnderChestBlockEntity)) return;
		watching = screen;
		BlockState state = mc.level.getBlockState(clicked);
		watchingBlock = state.getBlock().getName().getString();
		watchingPos = be instanceof EnderChestBlockEntity ? null : clicked;
		watchingPartner = null;
		if (watchingPos != null && state.getBlock() instanceof ChestBlock
			&& ChestBlock.getBlockType(state) != DoubleBlockCombiner.BlockType.SINGLE) {
			watchingPartner = clicked.relative(ChestBlock.getConnectedDirection(state));
		}
		clicked = null;
	}

	/** Stores what the watched screen held when it closes. */
	private void stopWatching() {
		if (watching != null && watchingItems != null && db != null) {
			ChestDatabase.Entry entry = new ChestDatabase.Entry(watchingPos, watchingPartner, watchingBlock, System.currentTimeMillis(), watchingItems);
			if (watchingPos == null) db.putEnderChest(entry);
			else db.put(dimension(), entry);
			if (chatNotice.get()) info("Recorded " + watchingBlock + " (" + watchingItems.size() + " stacks)");
			rebuild();
		}
		watching = null;
		watchingItems = null;
	}

	/** The container's own slots (not the player's inventory), with shulker and bundle contents flattened. */
	private List<ChestDatabase.Item> read(AbstractContainerScreen<?> screen) {
		var inventory = mc().player.getInventory();
		List<ChestDatabase.Item> items = new ArrayList<>();
		for (Slot slot : screen.getMenu().slots) {
			if (slot.container == inventory) continue;
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) continue;
			items.add(item(stack, false));
			ItemContainerContents inside = stack.get(DataComponents.CONTAINER);
			if (inside != null) inside.nonEmptyItemCopyStream().forEach(s -> items.add(item(s, true)));
			BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
			if (bundle != null) bundle.itemCopyStream().forEach(s -> items.add(item(s, true)));
		}
		return List.copyOf(items);
	}

	private static ChestDatabase.Item item(ItemStack stack, boolean nested) {
		return new ChestDatabase.Item(ItemIds.id(stack.getItem()), stack.getHoverName().getString(), stack.getCount(), nested);
	}

	/** Drops recorded containers in loaded chunks whose block is no longer a container. */
	private void forgetMissing() {
		Minecraft mc = mc();
		List<BlockPos> gone = new ArrayList<>();
		for (ChestDatabase.Entry entry : db.in(dimension())) {
			BlockPos pos = entry.pos();
			if (!mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;
			BlockEntity be = mc.level.getBlockEntity(pos);
			if (!(be instanceof Container)) gone.add(pos);
		}
		for (BlockPos pos : gone) db.remove(dimension(), pos);
	}

	// ---- searching ----

	/** Recomputes matches and boxes (on search change, recording, and once a second for distances). */
	private void rebuild() {
		Minecraft mc = mc();
		if (db == null || mc.player == null || mc.level == null) {
			matches = List.of();
			boxes = new EspRender.BlockSet();
			return;
		}
		String query = search.get().trim().toLowerCase(Locale.ROOT);
		Vec3 eyes = mc.player.getEyePosition();
		double maxRange = range.get() < 1 ? Double.MAX_VALUE : range.get();
		List<Match> found = new ArrayList<>();
		List<BlockPos> recorded = new ArrayList<>();
		for (ChestDatabase.Entry entry : db.in(dimension())) {
			double distance = Math.sqrt(entry.pos().distToCenterSqr(eyes));
			if (!query.isEmpty()) {
				int count = entry.count(query);
				if (count > 0) found.add(new Match(entry, count, distance));
			}
			if (markOpened.get() && distance <= maxRange) {
				recorded.add(entry.pos());
				if (entry.partner() != null) recorded.add(entry.partner());
			}
		}
		ChestDatabase.Entry ender = db.enderChest();
		if (!query.isEmpty() && ender != null && ender.count(query) > 0) found.add(new Match(ender, ender.count(query), 0));
		found.sort(Comparator.comparingDouble(Match::distance));
		if (found.size() > maxResults.intValue()) found = found.subList(0, maxResults.intValue());
		matches = List.copyOf(found);

		EspRender.BlockSet set = new EspRender.BlockSet();
		if (highlight.get()) {
			List<BlockPos> hits = new ArrayList<>();
			for (Match m : matches) {
				if (m.entry().pos() == null || m.distance() > maxRange) continue;
				hits.add(m.entry().pos());
				if (m.entry().partner() != null) hits.add(m.entry().partner());
			}
			set.add(hits, matchColor.argb() & 0xFFFFFF, "", true);
			recorded.removeAll(hits);
		}
		set.add(recorded, openedColor.argb() & 0xFFFFFF, "", true);
		boxes = set;
	}

	@Override
	public void renderWorld(PoseStack poseStack, Vec3 cam) {
		EspRender.BlockSet set = boxes;
		if (set.isEmpty()) return;
		set.render(shape.get(), seeThrough.get(), 1.5f, opacity.intValue(), cam);
		if (tracers.get() && tracerStyle.get() == Tracers.Style.Smooth) set.tracers(0x99, 1f, cam);
	}

	@Override
	public void renderOverlay(Ui ui, WorldProjector projector) {
		if (tracers.get() && tracerStyle.get() == Tracers.Style.Classic) boxes.screenTracers(ui, projector, 0x99, 1f);
		if (labels.get() && highlight.get()) {
			for (Match m : matches) {
				BlockPos pos = m.entry().pos();
				if (pos == null) continue;
				float[] point = projector.project(pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5);
				if (!projector.onScreen(point, 30)) continue;
				Nametag.draw(ui, point[0], point[1], List.of(new Nametag.Line("x" + m.count(), 0xFF000000 | matchColor.argb())),
					1f, true, -1, 0, null);
			}
		}
		if (lookPreview.get()) drawPreview(ui, projector);
	}

	/** The contents of the known container under the crosshair, grouped by item, beside the crosshair. */
	private void drawPreview(Ui ui, WorldProjector projector) {
		Minecraft mc = mc();
		if (db == null || mc.gui.screen() != null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
		BlockEntity be = mc.level.getBlockEntity(hit.getBlockPos());
		ChestDatabase.Entry entry = be instanceof EnderChestBlockEntity ? db.enderChest() : be instanceof Container ? db.at(dimension(), hit.getBlockPos()) : null;
		if (entry == null) return;

		Map<String, Integer> totals = new LinkedHashMap<>();
		for (ChestDatabase.Item item : entry.items()) if (!item.nested()) totals.merge(item.name(), item.count(), Integer::sum);
		List<Nametag.Line> lines = new ArrayList<>();
		lines.add(new Nametag.Line(entry.block() + " · " + age(entry.time()), ui.theme.accent));
		if (totals.isEmpty()) lines.add(new Nametag.Line("Empty", ui.theme.dimText));
		List<Map.Entry<String, Integer>> sorted = new ArrayList<>(totals.entrySet());
		sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
		for (int i = 0; i < Math.min(8, sorted.size()); i++) {
			lines.add(new Nametag.Line(sorted.get(i).getValue() + "x " + sorted.get(i).getKey(), 0xFFFFFFFF));
		}
		if (sorted.size() > 8) lines.add(new Nametag.Line("+" + (sorted.size() - 8) + " more", ui.theme.dimText));
		Nametag.draw(ui, projector.width() / 2f + 70, projector.height() / 2f - 10, lines, 1f, true, -1, 0, null);
	}

	private static String age(long time) {
		long minutes = (System.currentTimeMillis() - time) / 60_000;
		if (minutes < 1) return "just now";
		if (minutes < 60) return minutes + " min ago";
		long hours = minutes / 60;
		return hours < 48 ? hours + " h ago" : hours / 24 + " d ago";
	}

	private List<String[]> hudLines() {
		List<String[]> rows = new ArrayList<>();
		if (matches.isEmpty()) {
			rows.add(ModuleHudElement.row(search.get(), "not found"));
			return rows;
		}
		for (int i = 0; i < Math.min(10, matches.size()); i++) {
			Match m = matches.get(i);
			BlockPos p = m.entry().pos();
			String where = p == null ? "Ender chest" : Coords.format(p.getX()) + " " + Coords.format(p.getY()) + " " + Coords.format(p.getZ()) + String.format(Locale.ROOT, " (%.0fm)", m.distance());
			rows.add(ModuleHudElement.row(where, "x" + m.count()));
		}
		if (matches.size() > 10) rows.add(ModuleHudElement.row("", "+" + (matches.size() - 10) + " more"));
		return rows;
	}

	@Override
	public String infoString() {
		return db == null ? null : Integer.toString(db.size());
	}

	private String dimension() {
		return mc().level.dimension().identifier().toString();
	}

	private static String serverKey(Minecraft mc) {
		String server = "singleplayer";
		if (mc.getSingleplayerServer() != null) {
			server = "singleplayer_" + mc.getSingleplayerServer().getWorldData().getLevelName();
		} else if (mc.getConnection() != null) {
			ServerData data = mc.getConnection().getServerData();
			if (data != null && data.ip != null && !data.ip.isBlank()) server = data.ip;
		}
		String clean = server.replaceAll("[^A-Za-z0-9._-]", "_");
		return clean.length() > 64 ? clean.substring(0, 64) : clean;
	}
}
