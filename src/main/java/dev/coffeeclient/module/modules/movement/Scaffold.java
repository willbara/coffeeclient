package dev.coffeeclient.module.modules.movement;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.BlockListSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.BlockActions;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.Player;
import dev.coffeeclient.util.Rotations;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Places blocks under you as you walk, so you can bridge and tower: the
 * target under your feet (or ahead of you), the nearest spot to place at when that one has no face to
 * click, air placement around the target, and fast tower. The nearest-spot search also
 * looks past the block under you. Blocks are ranked (favourable blocks, then the biggest stack), with Same Y,
 * Expand, the safe edge and the block count. Placing goes through {@link BlockActions}, so the swap, the click and the
 * swap back share one packet batch.
 */
public final class Scaffold extends Module {
	public enum ListMode {
		Blacklist, Whitelist
	}

	/** Which height to keep placing at: the block under where you last stood on the ground. */
	public enum SameY {
		Off, On, JumpKey, Falling
	}

	/** Never placed: they hurt, trap or are not solid. */
	private static final Set<Block> DISALLOWED = Set.of(Blocks.TNT, Blocks.COBWEB, Blocks.NETHER_PORTAL);
	/** Only placed when nothing better is in the hotbar. */
	private static final Set<Block> UNFAVORABLE = Set.of(Blocks.CRAFTING_TABLE, Blocks.JIGSAW, Blocks.SMITHING_TABLE,
		Blocks.FLETCHING_TABLE, Blocks.ENCHANTING_TABLE, Blocks.CAULDRON, Blocks.MAGMA_BLOCK);

	public final BlockListSetting blocks = group(new BlockListSetting("Blocks", "Blocks to place, used according to the list mode", List.of()), "General");
	public final EnumSetting<ListMode> listMode = group(new EnumSetting<>("List mode", "Blacklist: any block except these. Whitelist: only these", ListMode.Blacklist), "General");
	public final BooleanSetting onlyOnClick = group(new BooleanSetting("Only on click", "Only place while holding use", false), "General");
	public final NumberSetting delay = group(new NumberSetting("Delay", "Ticks to wait after each placement", 0, 0, 10, 1), "General");
	public final BooleanSetting autoSwitch = group(new BooleanSetting("Auto switch", "Swap to a block before placing", true), "General");
	public final BooleanSetting rotate = group(new BooleanSetting("Rotate", "Turn (server side) towards each block as it is placed", true), "General");
	public final BooleanSetting swing = group(new BooleanSetting("Swing", "Swing your hand when placing", false), "General");
	public final BooleanSetting safeEdge = group(new BooleanSetting("Safe edge", "Stop at ledges while you have no block to place", true), "General");

	public final BooleanSetting airPlace = group(new BooleanSetting("Air place", "Place in the air around the target, not only against a solid face (the server must allow it)", false), "Placement");
	public final NumberSetting radius = group(new NumberSetting("Radius", "Air place: how far around the target to place", 0, 0, 6, 1), "Placement");
	public final NumberSetting blocksPerTick = group(new NumberSetting("Blocks per tick", "Air place: most blocks placed in one tick (with Rotate on, one)", 3, 1, 10, 1), "Placement");
	public final NumberSetting aheadDistance = group(new NumberSetting("Ahead distance", "Place this far ahead in the direction you move", 0, 0, 1, 0.05), "Placement");
	public final NumberSetting placeRange = group(new NumberSetting("Place range", "How far to look for a spot when the block under you has no face to click", 4, 0, 8, 0.5), "Placement");
	public final EnumSetting<SameY> sameY = group(new EnumSetting<>("Same Y", "Keep placing at the height you last stood on: always, unless jump is held, or unless you are rising", SameY.Off), "Placement");
	public final NumberSetting expand = group(new NumberSetting("Expand", "Place up to this many blocks ahead along where you face, so a bridge is ready before you walk (0 = only under you)", 0, 0, 10, 1), "Placement");

	public final BooleanSetting fastTower = group(new BooleanSetting("Fast tower", "While holding jump, move up so blocks can be stacked under you", false), "Tower");
	public final NumberSetting towerSpeed = group(new NumberSetting("Tower speed", "Upward speed while towering, in blocks per tick", 0.5, 0, 1, 0.05), "Tower");
	public final BooleanSetting whileMoving = group(new BooleanSetting("While moving", "Keep towering while you also walk", false), "Tower");

	public final BooleanSetting render = group(new BooleanSetting("Render", "Box the blocks you just placed", true), "Render");
	public final NumberSetting renderTime = group(new NumberSetting("Render time", "Ticks a box stays", 8, 1, 40, 1), "Render");
	public final ColorSetting color = group(new ColorSetting("Color", "Box colour", 0xFFC589E8), "Render");
	public final EnumSetting<WorldOverlays.Shape> shape = group(new EnumSetting<>("Shape", "Box style", WorldOverlays.Shape.Both), "Render");

	/** Blocks placed recently, with the ticks left to draw each one. */
	private final Map<BlockPos, Integer> shown = new HashMap<>();
	private int delayLeft;
	/** Height of the block under where you last stood on the ground (for Same Y). */
	private int placementY;

	public Scaffold() {
		super("Scaffold", Category.MOVEMENT, "Places blocks under you as you walk.");
		radius.visibleWhen(airPlace::get);
		blocksPerTick.visibleWhen(airPlace::get);
		aheadDistance.visibleWhen(() -> !airPlace.get());
		placeRange.visibleWhen(() -> !airPlace.get());
		expand.visibleWhen(() -> !airPlace.get());
		towerSpeed.visibleWhen(fastTower::get);
		whileMoving.visibleWhen(fastTower::get);
		renderTime.visibleWhen(render::get);
		color.visibleWhen(render::get);
		shape.visibleWhen(render::get);
		listen(Events.Tick.class, e -> tick());
		listen(Events.ClipAtLedge.class, e -> {
			if (safeEdge.get() && scaffolding() && outOfBlocks()) e.clip = true;
		});
		listen(Events.WorldChange.class, e -> reset());
		listen(Events.GameLeft.class, e -> reset());
	}

	@Override
	protected void onEnable() {
		LocalPlayer player = mc().player;
		if (player != null) placementY = player.blockPosition().getY() - 1;
	}

	@Override
	protected void onDisable() {
		reset();
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return blocks;
	}

	private void reset() {
		shown.clear();
		delayLeft = 0;
	}

	private void tick() {
		LocalPlayer player = mc().player;
		ClientLevel level = mc().level;
		if (player == null || level == null) return;

		if (!shown.isEmpty()) {
			shown.replaceAll((pos, ticks) -> ticks - 1);
			shown.values().removeIf(ticks -> ticks <= 0);
		}
		if (!scaffolding()) return;

		if (player.onGround()) placementY = player.blockPosition().getY() - 1;
		boolean fast = fastTowering(player, level);
		BlockPos target = target(player, level, fast);
		if (delayLeft > 0) delayLeft--;
		else placeAt(player, level, target);
		if (fast) moveUp(player, level);
	}

	/** Whether the use key is needed and held, or no key is needed at all. */
	private boolean scaffolding() {
		return !onlyOnClick.get() || mc().options.keyUse.isDown();
	}

	private boolean fastTowering(LocalPlayer player, ClientLevel level) {
		return fastTower.get() && mc().options.keyJump.isDown() && !mc().options.keyShift.isDown()
			&& (whileMoving.get() || !Player.isMoving())
			&& usable(findBlock(player.blockPosition().below(), level));
	}

	/** The spot to aim for this tick: under your feet, the way you walk, or below when sneaking down. */
	private BlockPos target(LocalPlayer player, ClientLevel level, boolean fast) {
		Vec3 below = player.position().add(player.getDeltaMovement()).add(0, -0.75, 0);
		BlockPos pos;
		if (airPlace.get()) {
			pos = BlockPos.containing(below);
		} else {
			Vec3 feet = player.position();
			BlockPos ground = player.blockPosition().below();
			if (aheadDistance.get() != 0 && !fast && !level.getBlockState(ground).getCollisionShape(level, ground).isEmpty()) {
				feet = feet.add(ahead(player));
			}
			pos = BlockPos.containing(feet.x, below.y, feet.z);
		}
		boolean jump = mc().options.keyJump.isDown();
		boolean down = mc().options.keyShift.isDown() && !jump;
		if (sameYActive(player, fast, jump, down)) pos = new BlockPos(pos.getX(), placementY, pos.getZ());
		else if (down && pos.getY() > level.getMinY()) pos = pos.below();
		if (pos.getY() >= player.blockPosition().getY()) pos = player.blockPosition().below();
		return pos;
	}

	/** Same Y is on for the chosen mode, and nothing else (towering, standing and jumping, sneaking down) is in charge of the height. */
	private boolean sameYActive(LocalPlayer player, boolean fast, boolean jump, boolean down) {
		if (fast || down || (jump && (!Player.isMoving() || player.horizontalCollision))) return false;
		return switch (sameY.get()) {
			case Off -> false;
			case On -> true;
			case JumpKey -> !jump;
			case Falling -> player.getDeltaMovement().y < 0.2;
		};
	}

	/** Shift of the ahead point, from the movement keys and the yaw. */
	private Vec3 ahead(LocalPlayer player) {
		double d = aheadDistance.get();
		Vec3 dir = Vec3.directionFromRotation(0, player.getYRot()).multiply(d, 0, d);
		Vec3 offset = Vec3.ZERO;
		if (mc().options.keyUp.isDown()) offset = offset.add(dir.x, 0, dir.z);
		if (mc().options.keyDown.isDown()) offset = offset.add(-dir.x, 0, -dir.z);
		if (mc().options.keyLeft.isDown()) offset = offset.add(dir.z, 0, -dir.x);
		if (mc().options.keyRight.isDown()) offset = offset.add(-dir.z, 0, dir.x);
		return offset;
	}

	private void placeAt(LocalPlayer player, ClientLevel level, BlockPos target) {
		if (!airPlace.get() && expand.intValue() == 0 && !level.getBlockState(target).canBeReplaced()) return;
		Inv.FindItemResult item = findBlock(target, level);
		if (!usable(item)) return;
		Block block = ((BlockItem) Inv.get(item.slot()).getItem()).getBlock();

		if (airPlace.get()) placeAir(player, target, item, block);
		else placeGround(player, level, item, target, block);
	}

	/** A spot to place at, with where to click it. */
	private record Spot(BlockPos pos, BlockActions.PlaceParams params) {
	}

	private void placeGround(LocalPlayer player, ClientLevel level, Inv.FindItemResult item, BlockPos target, Block block) {
		double reach = player.blockInteractionRange();
		Spot spot = expand.intValue() > 0 ? expandSpot(player, target, block, reach * reach) : null;
		if (spot == null && level.getBlockState(target).canBeReplaced()) {
			spot = clickable(target, block, reach * reach);
			if (spot == null) spot = nearestSpot(player, target, block, reach * reach);
		}
		if (spot != null && placeNow(spot.params().hitResult(), item)) onPlaced(spot.pos());
	}

	/** The first spot on the line ahead of the target (Expand) that a block can be placed at. */
	private Spot expandSpot(LocalPlayer player, BlockPos target, Block block, double reachSq) {
		double yaw = Math.toRadians(player.getYRot());
		for (int i = 0; i <= expand.intValue(); i++) {
			Spot spot = clickable(target.offset((int) (-Math.sin(yaw) * i), 0, (int) (Math.cos(yaw) * i)), block, reachSq);
			if (spot != null) return spot;
		}
		return null;
	}

	/** {@code pos} with its click, if a block can go there now and the click is in reach and needs no sneaking. */
	private Spot clickable(BlockPos pos, Block block, double reachSq) {
		if (!BlockActions.canPlace(pos, block, true)) return null;
		BlockActions.PlaceParams params = BlockActions.placeParams(pos);
		if (params == null || params.requiresSneaking() || params.distanceSq() > reachSq) return null;
		return new Spot(pos, params);
	}

	/**
	 * Clicks {@code hit} right away, before this tick's physics moves you (a falling player would
	 * otherwise be inside the block by the time a rotated click went out). With Rotate on, the look is
	 * sent with the next movement packet, as vanilla placement does. Only one rotated click
	 * fits in a tick, since a second rotation request is refused.
	 */
	private boolean placeNow(BlockHitResult hit, Inv.FindItemResult item) {
		if (rotate.get() && !Rotations.rotate(hit.getLocation(), Rotations.NORMAL, null)) return false;
		return BlockActions.placeAt(hit, item, false, Rotations.NORMAL, swing.get(), true);
	}

	/** The spot closest to {@code target} within place range that has a face to click, or null. */
	private Spot nearestSpot(LocalPlayer player, BlockPos target, Block block, double reachSq) {
		int r = (int) Math.ceil(placeRange.get());
		BlockPos base = player.blockPosition();
		Spot best = null;
		double bestDist = Double.MAX_VALUE;
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					BlockPos pos = base.offset(dx, dy, dz);
					double dist = pos.distSqr(target);
					if (dist >= bestDist) continue;
					Spot spot = clickable(pos, block, reachSq);
					if (spot == null) continue;
					best = spot;
					bestDist = dist;
				}
			}
		}
		return best;
	}

	/** Air placement: click the air block under each spot, as if it were a solid face. */
	private void placeAir(LocalPlayer player, BlockPos target, Inv.FindItemResult item, Block block) {
		int r = radius.intValue();
		Vec3 feet = player.position();
		List<BlockPos> spots = new ArrayList<>();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				BlockPos pos = new BlockPos(target.getX() + dx, target.getY(), target.getZ() + dz);
				if ((dx == 0 && dz == 0) || Vec3.atCenterOf(pos).distanceTo(feet) <= r) spots.add(pos);
			}
		}
		spots.sort(Comparator.comparingDouble(pos -> feet.distanceToSqr(Vec3.atCenterOf(pos))));

		double reach = player.blockInteractionRange();
		int placed = 0;
		for (BlockPos pos : spots) {
			if (placed >= blocksPerTick.intValue()) break;
			if (!BlockActions.canPlace(pos, block, true)) continue;
			// The top face of the air block below the spot is where the click lands.
			Vec3 hit = new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
			if (hit.distanceToSqr(player.getEyePosition()) > reach * reach) continue;
			BlockHitResult click = new BlockHitResult(hit, Direction.UP, pos.below(), false);
			if (placeNow(click, item)) {
				onPlaced(pos);
				placed++;
			}
		}
	}

	private void onPlaced(BlockPos pos) {
		if (render.get()) shown.put(pos.immutable(), renderTime.intValue());
		delayLeft = delay.intValue();
	}

	/** Fast tower: rise while nothing is above you, or drop back onto the block you just placed. */
	private void moveUp(LocalPlayer player, ClientLevel level) {
		Vec3 velocity = player.getDeltaMovement();
		AABB box = player.getBoundingBox();
		if (!level.getBlockCollisions(player, box.move(0, 1, 0)).iterator().hasNext()) {
			if (whileMoving.get() || !Player.isMoving()) velocity = new Vec3(velocity.x, towerSpeed.get(), velocity.z);
			player.setDeltaMovement(velocity);
		} else {
			player.setDeltaMovement(velocity.x, Math.ceil(player.getY()) - player.getY(), velocity.z);
			player.setOnGround(true);
		}
	}

	/** Whether the held item can be placed: it is in reach of the slot logic and the auto switch allows it. */
	private boolean usable(Inv.FindItemResult item) {
		LocalPlayer player = mc().player;
		if (!item.found() || player == null) return false;
		return autoSwitch.get() || item.isOffhand() || item.slot() == player.getInventory().getSelectedSlot();
	}

	/**
	 * The slot to place from: the offhand, the held slot and (with Auto switch) the rest of the hotbar,
	 * ranked as in : favourable blocks before unfavourable ones, then the bigger stack.
	 */
	private Inv.FindItemResult findBlock(BlockPos pos, ClientLevel level) {
		LocalPlayer player = mc().player;
		if (player == null) return Inv.FindItemResult.NONE;
		int held = player.getInventory().getSelectedSlot();
		int bestSlot = -1, bestCount = 0;
		boolean bestBad = true;
		for (int i = -2; i <= 8; i++) {
			if (i >= 0 && (i == held || !autoSwitch.get())) continue;
			int slot = i == -2 ? Inv.OFFHAND_INDEX : i == -1 ? held : i;
			ItemStack stack = Inv.get(slot);
			if (!validItem(stack, pos, level)) continue;
			boolean bad = unfavourable(((BlockItem) stack.getItem()).getBlock(), level);
			if (bestSlot < 0 || (bestBad && !bad) || (bestBad == bad && stack.getCount() > bestCount)) {
				bestSlot = slot;
				bestBad = bad;
				bestCount = stack.getCount();
			}
		}
		return bestSlot < 0 ? Inv.FindItemResult.NONE : new Inv.FindItemResult(bestSlot, bestCount);
	}

	private boolean validItem(ItemStack stack, BlockPos pos, ClientLevel level) {
		LocalPlayer player = mc().player;
		if (player == null || !(stack.getItem() instanceof BlockItem item)) return false;
		Block block = item.getBlock();
		if (DISALLOWED.contains(block) || !listAllows(block)) return false;
		if (!block.defaultBlockState().entityCanStandOnFace(level, pos, player, Direction.UP)) return false;
		return !(block instanceof FallingBlock) || !FallingBlock.isFree(level.getBlockState(pos.below()));
	}

	/** Slippery, slowing, sticky, a block entity, not a full cube, or on the avoid list: used only as a last resort. */
	private static boolean unfavourable(Block block, ClientLevel level) {
		return block.getFriction() > 0.6F || block.getSpeedFactor() < 1.0F || block.getJumpFactor() < 1.0F
			|| block instanceof BaseEntityBlock || !block.defaultBlockState().isCollisionShapeFullBlock(level, BlockPos.ZERO)
			|| UNFAVORABLE.contains(block);
	}

	private boolean outOfBlocks() {
		LocalPlayer player = mc().player;
		ClientLevel level = mc().level;
		return player == null || level == null || !usable(findBlock(player.blockPosition().below(), level));
	}

	/** Blocks that can be placed from the hotbar and offhand, shown beside the name in the module list. */
	@Override
	public String infoString() {
		LocalPlayer player = mc().player;
		ClientLevel level = mc().level;
		if (player == null || level == null) return null;
		BlockPos below = player.blockPosition().below();
		int count = 0;
		for (int slot = -1; slot <= 8; slot++) {
			ItemStack stack = Inv.get(slot == -1 ? Inv.OFFHAND_INDEX : slot);
			if (validItem(stack, below, level)) count += stack.getCount();
		}
		return Integer.toString(count);
	}

	private boolean listAllows(Block block) {
		return listMode.get() == ListMode.Blacklist ? !blocks.has(block) : blocks.has(block);
	}

	@Override
	public void renderWorld(PoseStack poseStack, Vec3 cameraPos) {
		if (!render.get() || shown.isEmpty()) return;
		// Each box fades its fill as its time runs out; off-screen ones are skipped.
		var frustum = EspRender.frustum();
		int rgb = color.argb() & 0xFFFFFF, alpha = (color.argb() >>> 24) / 3;
		for (Map.Entry<BlockPos, Integer> e : shown.entrySet()) {
			AABB box = new AABB(e.getKey());
			if (!frustum.isVisible(box)) continue;
			EspRender.boxes(List.of(box), new int[]{rgb}, shape.get(), false, 1.5f, alpha * e.getValue() / renderTime.intValue(), cameraPos);
		}
	}
}
