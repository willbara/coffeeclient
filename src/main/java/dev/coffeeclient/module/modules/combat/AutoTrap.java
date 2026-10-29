package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Footprint;
import dev.coffeeclient.util.Holes;
import dev.coffeeclient.util.Rotations;
import dev.coffeeclient.util.Targets;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Traps an enemy from above and around the head. The cells at their feet stay open, so your crystal
 * spots there are never closed: Smart only blocks the head-height cells above an open feet cell
 * (their way out), Full the whole head ring.
 */
public final class AutoTrap extends PlaceModule {
	public enum Face { Off, Smart, Full }

	public final NumberSetting targetRange = group(new NumberSetting("Target range", "Farthest enemy to trap", 5.5, 1, 8, 0.5), "AutoTrap");
	public final BooleanSetting top = group(new BooleanSetting("Top", "Cover the enemy's head", true), "AutoTrap");
	public final EnumSetting<Face> face = group(new EnumSetting<>("Face", "Head-height ring: Smart blocks only their ways out, Full all of it", Face.Smart), "AutoTrap");
	public final BooleanSetting onlyStill = group(new BooleanSetting("Only still", "Wait until the enemy stands still or sits in a hole", false), "AutoTrap");

	private Player target;

	public AutoTrap() {
		super("AutoTrap", List.of("Auto Trap"), "Traps enemies from above and around the head.", List.of("minecraft:obsidian"), false);
	}

	@Override
	protected void onDisable() {
		super.onDisable();
		target = null;
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null) return;
		target = Targets.playerTarget(targetRange.get(), Targets.SortPriority.LowestDistance);
		if (target == null) return;
		Holes.Blocks blocks = Footprint.blocks(mc().level);
		AABB box = target.getBoundingBox();
		LongSet columns = Footprint.columns(box, blocks);
		boolean moving = target.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4;
		if (onlyStill.get() && moving && !Footprint.walled(columns, blocks, false)) return;

		List<BlockPos> cells = new ArrayList<>();
		if (face.get() != Face.Off) {
			for (BlockPos wall : Footprint.ring(columns)) {
				if (face.get() == Face.Full || mc().level.getBlockState(wall).canBeReplaced()) cells.add(wall.above());
			}
		}
		if (top.get()) {
			int level = BlockPos.getY(columns.iterator().nextLong());
			int topY = target.onGround() ? Mth.floor(box.maxY - 1.0E-4) + 1 : level + 2;
			for (long key : columns) cells.add(new BlockPos(BlockPos.getX(key), topY, BlockPos.getZ(key)));
		}
		place(cells, Rotations.NORMAL);
	}

	@Override
	public String infoString() {
		return target == null ? null : target.getName().getString();
	}
}
