package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.Footprint;
import dev.coffeeclient.util.Holes;
import dev.coffeeclient.util.MotionTracker;
import dev.coffeeclient.util.Rotations;
import dev.coffeeclient.util.Targets;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Fills crystal-safe holes (blast-proof walls) so enemies can't hide in them. Smart only fills holes
 * an enemy is about to step into: one walking, not already in a safe hole, with its feet (a few
 * ticks ahead) close to the hole.
 */
public final class HoleFill extends PlaceModule {
	public final NumberSetting radius = group(new NumberSetting("Search radius", "How far around you to look for holes", 5, 1, 6, 1), "HoleFill");
	public final BooleanSetting doubles = group(new BooleanSetting("Doubles", "Also fill two-block holes", true), "HoleFill");
	public final BooleanSetting smart = group(new BooleanSetting("Smart", "Only fill holes an enemy is about to step into", true), "HoleFill");
	public final NumberSetting targetRange = group(new NumberSetting("Target range", "Enemies farther than this are ignored", 7, 1, 12, 0.5), "HoleFill");
	public final NumberSetting feetRange = group(new NumberSetting("Feet range", "How close an enemy's feet must be to a hole", 1.5, 0.5, 4, 0.25), "HoleFill");
	public final NumberSetting predict = group(new NumberSetting("Predict ticks", "Judge enemies by where they will be this many ticks ahead", 4, 0, 20, 1), "HoleFill");
	public final BooleanSetting ignoreSafe = group(new BooleanSetting("Ignore safe", "Skip enemies already standing in a safe hole", true), "HoleFill");
	public final BooleanSetting onlyMoving = group(new BooleanSetting("Only moving", "Skip enemies standing still", true), "HoleFill");

	private int holes;

	public HoleFill() {
		super("HoleFill", List.of("HoleFiller", "Hole Filler"), "Fills holes enemies could hide in.", List.of("minecraft:obsidian"), false);
		for (Setting<?> s : List.of(targetRange, feetRange, predict, ignoreSafe, onlyMoving)) s.visibleWhen(smart::get);
	}

	@Override
	protected void onDisable() {
		super.onDisable();
		holes = 0;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null) return;
		List<Vec3> feet = smart.get() ? enemyFeet(player) : List.of();
		holes = 0;
		if (smart.get() && feet.isEmpty() || !hasBlocks()) return;

		Holes.Blocks blocks = Footprint.blocks(mc().level);
		BlockPos origin = player.blockPosition();
		int r = radius.intValue();
		record Found(BlockPos cell, double distance) {
		}
		List<Found> found = new ArrayList<>();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dy = -r; dy <= 1; dy++) {
					Holes.Hole hole = Holes.find(blocks, origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz, 2);
					if (hole == null || hole.safety() == Holes.Safety.UNSAFE || !doubles.get() && hole.type() != Holes.Type.SINGLE) continue;
					for (int x = 0; x < hole.sizeX(); x++) {
						for (int z = 0; z < hole.sizeZ(); z++) {
							BlockPos cell = new BlockPos(hole.x() + x, hole.y(), hole.z() + z);
							double distance = smart.get() ? nearest(feet, cell) : 0;
							if (distance <= feetRange.get()) found.add(new Found(cell, distance));
						}
					}
					holes++;
				}
			}
		}
		found.sort(Comparator.comparingDouble(Found::distance));
		place(found.stream().map(Found::cell).toList(), Rotations.NORMAL);
	}

	/** Feet positions, a few ticks ahead, of enemies who could step into a hole now. */
	private List<Vec3> enemyFeet(LocalPlayer player) {
		List<Vec3> feet = new ArrayList<>();
		Holes.Blocks blocks = Footprint.blocks(mc().level);
		for (Player other : mc().level.players()) {
			if (!Targets.isAttackablePlayer(other) || other.distanceToSqr(player) > targetRange.get() * targetRange.get()) continue;
			if (onlyMoving.get() && other.position().equals(new Vec3(other.xo, other.yo, other.zo))) continue;
			if (ignoreSafe.get() && Footprint.walled(Footprint.columns(other.getBoundingBox(), blocks), blocks, true)) continue;
			feet.add(MotionTracker.feet(MotionTracker.predict(other, predict.intValue())));
		}
		return feet;
	}

	/** Distance from the top middle of {@code cell} (where feet stand in it) to the nearest of {@code feet}. */
	private static double nearest(List<Vec3> feet, BlockPos cell) {
		Vec3 top = Vec3.upFromBottomCenterOf(cell, 1);
		double best = Double.MAX_VALUE;
		for (Vec3 f : feet) best = Math.min(best, f.distanceTo(top));
		return best;
	}

	@Override
	public String infoString() {
		return holes > 0 ? String.valueOf(holes) : null;
	}
}
