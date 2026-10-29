package dev.coffeeclient.util;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Comparator;

/**
 * One-call block placing and breaking for modules, wired through {@link Rotations} and {@link Inv}.
 *
 * <p>Choosing the face and the exact point to click: faces with
 * line of sight first, then the nearest (breaking) or farthest (placing, so the click lands on a
 * face pointing at you); hit points on the block's real shape; back faces never raytraced; neighbours
 * that would open a menu avoided. Client thread only.
 */
public final class BlockActions {
	private static boolean breaking;
	private static boolean breakingThisTick;

	private BlockActions() {
	}

	/** Where to click to place a block at {@code pos}: the neighbour, its face, and the exact point. */
	public record PlaceParams(BlockPos neighbor, Direction side, Vec3 hitVec, double distanceSq, boolean lineOfSight,
							  boolean requiresSneaking) {
		public BlockHitResult hitResult() {
			return new BlockHitResult(hitVec, side, neighbor, false);
		}
	}

	/** Where to hit {@code pos} to break it: the face and the exact point. */
	public record BreakParams(BlockPos pos, Direction side, Vec3 hitVec, double distanceSq, boolean lineOfSight) {
		public BlockHitResult hitResult() {
			return new BlockHitResult(hitVec, side, pos, false);
		}
	}

	public static void init(EventBus bus) {
		// Stop a dig nobody continued this tick (breakBlock contract: call it every tick).
		bus.subscribe(Events.Tick.class, EventBus.LOWEST, e -> {
			Minecraft mc = Minecraft.getInstance();
			if (breaking && !breakingThisTick) {
				breaking = false;
				if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
			}
			breakingThisTick = false;
		});
	}

	// ---- placing ----

	/**
	 * Places the block held in {@code item}'s slot at {@code pos}.
	 *
	 * @param item          from {@link Inv#findInHotbar}; must be in the hotbar or offhand
	 * @param rotate        face the click point first (the place happens on the next movement packet)
	 * @param checkEntities refuse when an entity is in the way
	 * @param swapBack      return to the previously held slot afterwards
	 * @return false when nothing will be placed (no item, no usable face, out of reach, blocked)
	 */
	public static boolean place(BlockPos pos, Inv.FindItemResult item, boolean rotate, int priority, boolean swing,
								boolean checkEntities, boolean swapBack) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || !(item.isHotbar() || item.isOffhand())) return false;
		ItemStack stack = player.getInventory().getItem(item.slot());
		Block block = stack.getItem() instanceof BlockItem blockItem ? blockItem.getBlock() : Blocks.OBSIDIAN;
		if (!canPlace(pos, block, checkEntities)) return false;

		PlaceParams params = placeParams(pos);
		if (params == null || params.requiresSneaking()) return false;
		double reach = player.blockInteractionRange();
		if (params.distanceSq() > reach * reach) return false;

		return placeAt(params.hitResult(), item, rotate, priority, swing, swapBack);
	}

	/**
	 * Places the block held in {@code item}'s slot by clicking {@code hit}, whatever is there. Use it for
	 * air placement, where {@code hit} names an air block and the face it should be placed against.
	 * The swap, the click and the swap back go out in one packet batch.
	 *
	 * @return false when there is no usable item, or when a higher-priority rotation already holds this tick
	 */
	public static boolean placeAt(BlockHitResult hit, Inv.FindItemResult item, boolean rotate, int priority, boolean swing,
								  boolean swapBack) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || !(item.isHotbar() || item.isOffhand())) return false;
		InteractionHand hand = item.isOffhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
		Runnable action = () -> PacketBatch.run(() -> {
			if (hand == InteractionHand.MAIN_HAND) Inv.swap(item.slot(), swapBack);
			interact(hit, hand, swing);
			if (swapBack && hand == InteractionHand.MAIN_HAND) Inv.swapBack();
		});
		if (rotate) return Rotations.rotate(hit.getLocation(), priority, action);
		action.run();
		return true;
	}

	/** True when {@code pos} is in the world, replaceable, and (optionally) free of entities for {@code block}. */
	public static boolean canPlace(BlockPos pos, Block block, boolean checkEntities) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || pos == null || !Level.isInSpawnableBounds(pos)) return false;
		if (!level.getBlockState(pos).canBeReplaced()) return false;
		return !checkEntities || level.isUnobstructed(block.defaultBlockState(), pos, CollisionContext.empty());
	}

	/**
	 * Picks the face to click to place at {@code pos}, or null if there is none. A replaceable block
	 * with a shape (tall grass, snow) is clicked itself; otherwise a solid neighbour is used.
	 */
	public static PlaceParams placeParams(BlockPos pos) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		if (level == null || player == null) return null;
		BlockState self = level.getBlockState(pos);
		if (self.canBeReplaced() && !self.getShape(level, pos).isEmpty()) {
			BreakParams b = breakParams(player.getEyePosition(), pos);
			if (b == null) return null;
			return new PlaceParams(pos, b.side(), b.hitVec(), b.distanceSq(), b.lineOfSight(), isInteractive(self));
		}

		Vec3 eyes = player.getEyePosition();
		double centerDistSq = eyes.distanceToSqr(Vec3.atCenterOf(pos));
		Direction best = null;
		Vec3 bestHit = null;
		double bestDist = 0;
		boolean bestLos = false, bestInteractive = false;
		for (Direction side : Direction.values()) {
			BlockPos neighbor = pos.relative(side);
			BlockState state = level.getBlockState(neighbor);
			VoxelShape shape = state.getShape(level, neighbor);
			if (shape.isEmpty() || state.canBeReplaced()) continue;

			// Middle of the neighbour's face that touches pos, on its real shape.
			AABB box = shape.bounds();
			Direction face = side.getOpposite();
			Vec3 center = Vec3.atLowerCornerOf(neighbor).add(box.getCenter());
			Vec3 hit = center.add((box.maxX - box.minX) / 2 * face.getStepX(), (box.maxY - box.minY) / 2 * face.getStepY(),
				(box.maxZ - box.minZ) / 2 * face.getStepZ());
			double dist = eyes.distanceToSqr(hit);
			boolean interactive = isInteractive(state);
			// A neighbour in front of pos would be clicked on its rear face: no line of sight possible.
			boolean los = dist > centerDistSq && hasLineOfSight(eyes, hit, neighbor);

			boolean better;
			if (best == null) better = true;
			else if (bestInteractive != interactive) better = !interactive;
			else if (bestLos != los) better = los;
			else better = dist > bestDist;
			if (better) {
				best = side;
				bestHit = hit;
				bestDist = dist;
				bestLos = los;
				bestInteractive = interactive;
			}
		}
		if (best == null) return null;
		return new PlaceParams(pos.relative(best), best.getOpposite(), bestHit, bestDist, bestLos, bestInteractive);
	}

	/** Right-clicks {@code hit} with {@code hand} without sneaking; swings (or just tells the server) on success. */
	public static InteractionResult interact(BlockHitResult hit, InteractionHand hand, boolean swing) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null) return InteractionResult.FAIL;
		boolean wasSneaking = player.isShiftKeyDown();
		player.setShiftKeyDown(false);
		try {
			InteractionResult result = mc.gameMode.useItemOn(player, hand, hit);
			if (result.consumesAction()) swing(hand, swing);
			return result;
		} finally {
			player.setShiftKeyDown(wasSneaking);
		}
	}

	// ---- breaking ----

	/**
	 * Mines {@code pos}: starts or continues digging it. Call every tick while it should be mined; the
	 * dig is stopped at the end of a tick in which nobody called this.
	 *
	 * @param rotate face the chosen hit point first (digging then happens on the next movement packet)
	 */
	public static boolean breakBlock(BlockPos pos, boolean rotate, int priority, boolean swing) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gameMode == null) return false;
		BlockPos target = pos.immutable();
		BlockState state = mc.level.getBlockState(target);
		if (state.isAir() || state.getDestroySpeed(mc.level, target) < 0) return false;
		BreakParams params = breakParams(player.getEyePosition(), target);
		if (params == null) return false;
		double reach = player.blockInteractionRange();
		if (params.distanceSq() > reach * reach) return false;

		Runnable action = () -> {
			if (mc.gameMode.isDestroying()) mc.gameMode.continueDestroyBlock(target, params.side());
			else mc.gameMode.startDestroyBlock(target, params.side());
			swing(InteractionHand.MAIN_HAND, swing);
			breaking = true;
			breakingThisTick = true;
		};
		// Mark the tick even when the dig waits for the rotation, so it is not cancelled meanwhile.
		breakingThisTick = true;
		if (rotate) return Rotations.rotate(params.hitVec(), priority, action);
		action.run();
		return true;
	}

	/** Picks the face of {@code pos} to hit from {@code eyes}: line of sight first, then the nearest. */
	public static BreakParams breakParams(Vec3 eyes, BlockPos pos) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return null;
		VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
		if (shape.isEmpty()) return null;
		AABB box = shape.bounds();
		Vec3 center = Vec3.atLowerCornerOf(pos).add(box.getCenter());
		double centerDistSq = eyes.distanceToSqr(center);

		Direction best = null;
		Vec3 bestHit = null;
		double bestDist = 0;
		boolean bestLos = false;
		for (Direction side : Direction.values()) {
			Vec3 hit = center.add((box.maxX - box.minX) / 2 * side.getStepX(), (box.maxY - box.minY) / 2 * side.getStepY(),
				(box.maxZ - box.minZ) / 2 * side.getStepZ());
			double dist = eyes.distanceToSqr(hit);
			// Faces turned away from the eyes cannot be seen, so skip the raytrace.
			boolean los = dist < centerDistSq && hasLineOfSight(eyes, hit, pos);
			boolean better = best == null || (bestLos != los ? los : dist < bestDist);
			if (better) {
				best = side;
				bestHit = hit;
				bestDist = dist;
				bestLos = los;
			}
		}
		return new BreakParams(pos, best, bestHit, bestDist, bestLos);
	}

	/** Sorts many targets: reachable by line of sight first, then nearest. */
	public static Comparator<BreakParams> byReachability() {
		return Comparator.comparing(BreakParams::lineOfSight).reversed().thenComparingDouble(BreakParams::distanceSq);
	}

	// ---- shared ----

	/**
	 * True when nothing solid lies between {@code from} and {@code to}, other than {@code target}
	 * itself (the point usually sits on its surface, where a raytrace may clip it).
	 */
	public static boolean hasLineOfSight(Vec3 from, Vec3 to, BlockPos target) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return false;
		BlockHitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
		return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
	}

	private static void swing(InteractionHand hand, boolean visible) {
		Minecraft mc = Minecraft.getInstance();
		if (visible) mc.player.swing(hand);
		else if (mc.getConnection() != null) mc.getConnection().send(new ServerboundSwingPacket(hand));
	}

	/** Blocks that open a menu or toggle when right-clicked, so placing against them needs sneaking. */
	public static boolean isInteractive(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof AbstractChestBlock || block instanceof EnderChestBlock || block instanceof BarrelBlock
			|| block instanceof ShulkerBoxBlock || block instanceof DecoratedPotBlock || block instanceof ChiseledBookShelfBlock) return true;
		if (block instanceof AnvilBlock || block instanceof CartographyTableBlock || block instanceof CraftingTableBlock
			|| block instanceof EnchantingTableBlock || block instanceof GrindstoneBlock || block instanceof LoomBlock
			|| block instanceof StonecutterBlock || block instanceof SmithingTableBlock) return true;
		if (block instanceof AbstractFurnaceBlock || block instanceof BrewingStandBlock || block instanceof DispenserBlock
			|| block instanceof HopperBlock || block instanceof CrafterBlock || block instanceof BeaconBlock) return true;
		if (block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock
			|| block instanceof ButtonBlock || block instanceof LeverBlock || block instanceof BedBlock
			|| block instanceof RepeaterBlock || block instanceof ComparatorBlock || block instanceof NoteBlock
			|| block instanceof BellBlock || block instanceof DaylightDetectorBlock || block instanceof SignBlock) return true;
		if (block instanceof LecternBlock) return state.getValue(BlockStateProperties.HAS_BOOK);
		if (block instanceof CommandBlock || block instanceof StructureBlock || block instanceof JigsawBlock) {
			LocalPlayer player = Minecraft.getInstance().player;
			return player != null && player.canUseGameMasterBlocks();
		}
		return false;
	}
}
