package dev.coffeeclient.module.modules.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.BlockListSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.BlockActions;
import dev.coffeeclient.util.CombatTick;
import dev.coffeeclient.util.CrystalTracker;
import dev.coffeeclient.util.Damage;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.Latency;
import dev.coffeeclient.util.PacketBatch;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Base for combat modules that place blocks into a list of cells (Surround, HoleFill, traps): the
 * shared placing settings, the place loop with a support block under cells that have nothing to
 * click, crystals in the way broken when that can't kill you, cells waiting on the server skipped,
 * and recent placements drawn.
 */
public abstract class PlaceModule extends Module {
	private static final long RENDER_MS = 600;

	public final BlockListSetting blocks;
	public final NumberSetting perTick;
	public final NumberSetting delay;
	public final BooleanSetting rotate;
	public final BooleanSetting airPlace;
	public final BooleanSetting swing;
	public final BooleanSetting breakCrystals;
	public final BooleanSetting render;
	public final ColorSetting color;
	public final EnumSetting<WorldOverlays.Shape> shape;

	/** Cell -> when it was placed: skipped until the server had time to answer. Also what is drawn. */
	private final Long2LongOpenHashMap placed = new Long2LongOpenHashMap();
	/** Client tick from which placing may resume (Delay), and this tick's count, so extra passes in one tick share the budget. */
	private int resumeAt, budgetTick, used;

	/** {@code defaultBlocks} and {@code defaultAirPlace} are this module's defaults for Blocks and Air place. */
	protected PlaceModule(String name, List<String> aliases, String description, List<String> defaultBlocks, boolean defaultAirPlace) {
		super(name, aliases, Category.COMBAT, description);
		blocks = group(new BlockListSetting("Blocks", "Blocks to place; the first one found in your hotbar is used", defaultBlocks), "Place");
		perTick = group(new NumberSetting("Per tick", "Most blocks placed in one tick (with Rotate on, one per tick)", 4, 1, 8, 1), "Place");
		delay = group(new NumberSetting("Delay", "Ticks to wait after placing", 0, 0, 10, 1), "Place");
		rotate = group(new BooleanSetting("Rotate", "Face each block before placing it", true), "Place");
		airPlace = group(new BooleanSetting("Air place", "Place against nothing; without it, a support block goes under a cell that has no neighbour", defaultAirPlace), "Place");
		swing = group(new BooleanSetting("Swing", "Swing your hand when placing", true), "Place");
		breakCrystals = group(new BooleanSetting("Break crystals", "Hit a crystal sitting in a cell, unless the blast would kill you", true), "Place");
		render = group(new BooleanSetting("Render", "Box the blocks just placed", true), "Render");
		color = group(new ColorSetting("Color", "Box colour", 0xFF8060FF), "Render");
		shape = group(new EnumSetting<>("Shape", "Box style", WorldOverlays.Shape.Both), "Render");
		for (Setting<?> s : List.of(color, shape)) s.visibleWhen(render::get);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void onDisable() {
		placed.clear();
		resumeAt = used = 0;
		CombatTick.release(this);
	}

	/**
	 * Places into {@code cells} in order, as far as this tick's budget goes, at rotation and claim
	 * priority {@code priority}. Filled cells and ones still waiting on the server are skipped. Can be
	 * called more than once a tick (reacting to packets); the calls share the tick's budget.
	 *
	 * @return how many blocks were placed (support blocks included)
	 */
	protected int place(List<BlockPos> cells, int priority) {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null) return 0;
		long now = System.currentTimeMillis();
		long settle = Latency.rtt() + 100L;
		placed.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > Math.max(settle, RENDER_MS));
		if (player.tickCount != budgetTick) {
			budgetTick = player.tickCount;
			used = 0;
		}
		// One look per movement packet: with Rotate, one block per tick.
		int budget = (rotate.get() ? 1 : perTick.intValue()) - used;
		if (budget <= 0 || player.tickCount < resumeAt) return 0;
		if (cells.isEmpty() || CombatTick.lagging() || !CombatTick.claim(CombatTick.Kind.PLACE, this, priority, 1)) return 0;
		Inv.FindItemResult item = Inv.findInHotbar(this::placeable);
		if (!item.found()) return 0;
		Block block = ((BlockItem) player.getInventory().getItem(item.slot()).getItem()).getBlock();

		int count = 0;
		for (BlockPos cell : cells) {
			if (count >= budget) break;
			long key = cell.asLong();
			if (placed.containsKey(key) && now - placed.get(key) < settle || !BlockActions.canPlace(cell, block, false)) continue;
			Boolean entities = clear(cell, block);
			if (entities == null) continue;
			BlockPos target = cell;
			if (!airPlace.get() && BlockActions.placeParams(cell) == null) {
				// Nothing to click: put a support block under it first, the cell follows next round.
				target = cell.below();
				if (placed.containsKey(target.asLong()) || !BlockActions.canPlace(target, block, true) || BlockActions.placeParams(target) == null) continue;
				entities = true;
			}
			if (!placeOne(target, item, priority, entities)) continue;
			placed.put(target.asLong(), now);
			count++;
		}
		used += count;
		if (count > 0 && delay.intValue() > 0) resumeAt = player.tickCount + 1 + delay.intValue();
		return count;
	}

	private boolean placeOne(BlockPos pos, Inv.FindItemResult item, int priority, boolean checkEntities) {
		if (airPlace.get() && BlockActions.placeParams(pos) == null) {
			return BlockActions.placeAt(new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false), item, rotate.get(), priority, swing.get(), true);
		}
		return BlockActions.place(pos, item, rotate.get(), priority, swing.get(), checkEntities, true);
	}

	/**
	 * Gets entities out of {@code cell}: breaks crystals in it when allowed and safe.
	 *
	 * @return null when something stays in the way, false when the only things there are crystals we
	 *         broke (place without checking entities: the server removes them first), true when clear
	 */
	private Boolean clear(BlockPos cell, Block block) {
		if (BlockActions.canPlace(cell, block, true)) return true;
		LocalPlayer player = mc().player;
		boolean broke = false;
		for (Entity entity : mc().level.getEntities(null, new AABB(cell))) {
			if (entity instanceof EndCrystal dead && CrystalTracker.isDead(dead)) {
				broke = true;
				continue;
			}
			if (!(entity instanceof EndCrystal crystal) || !breakCrystals.get() || Damage.crystal(player, crystal.position()) >= Damage.health(player)) {
				if (entity.blocksBuilding) return null;
				continue;
			}
			PacketBatch.run(() -> {
				player.connection.send(new ServerboundAttackPacket(crystal.getId()));
				if (swing.get()) player.swing(InteractionHand.MAIN_HAND);
			});
			CrystalTracker.explodeAt(crystal.position());
			broke = true;
		}
		return broke ? Boolean.FALSE : null;
	}

	private boolean placeable(ItemStack stack) {
		return stack.getItem() instanceof BlockItem item && blocks.has(item.getBlock());
	}

	/** True when the hotbar or offhand holds one of the blocks. */
	protected boolean hasBlocks() {
		return Inv.findInHotbar(this::placeable).found();
	}

	@Override
	public void renderWorld(PoseStack poseStack, Vec3 cameraPos) {
		if (!render.get() || placed.isEmpty()) return;
		long now = System.currentTimeMillis();
		List<AABB> boxes = new ArrayList<>();
		for (var entry : placed.long2LongEntrySet()) {
			if (now - entry.getLongValue() <= RENDER_MS) boxes.add(new AABB(BlockPos.of(entry.getLongKey())));
		}
		if (boxes.isEmpty()) return;
		int[] colors = new int[boxes.size()];
		Arrays.fill(colors, color.argb() & 0xFFFFFF);
		EspRender.boxes(boxes, colors, shape.get(), false, 1.5f, (color.argb() >>> 24) / 3, cameraPos);
	}
}
