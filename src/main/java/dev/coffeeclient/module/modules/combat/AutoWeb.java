package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Footprint;
import dev.coffeeclient.util.Holes;
import dev.coffeeclient.util.Latency;
import dev.coffeeclient.util.MotionTracker;
import dev.coffeeclient.util.Rotations;
import dev.coffeeclient.util.Targets;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Webs the cells an enemy stands in, and with Predict the ones they will be in when your packet
 * lands. Smart leaves enemies in a hole alone: a web only slows someone who can still move.
 */
public final class AutoWeb extends PlaceModule {
	public final NumberSetting targetRange = group(new NumberSetting("Target range", "Farthest enemy to web", 5.5, 1, 8, 0.5), "AutoWeb");
	public final BooleanSetting smart = group(new BooleanSetting("Smart", "Only web enemies outside a hole", true), "AutoWeb");
	public final BooleanSetting predict = group(new BooleanSetting("Predict", "Also web where the enemy will be when your packet reaches the server", true), "AutoWeb");

	private Player target;

	public AutoWeb() {
		super("AutoWeb", List.of("Auto Web"), "Webs enemies where they stand.", List.of("minecraft:cobweb"), true);
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
		LongSet columns = Footprint.columns(target.getBoundingBox(), blocks);
		if (smart.get() && Footprint.walled(columns, blocks, false)) return;
		if (predict.get()) columns.addAll(Footprint.columns(MotionTracker.predict(target, Mth.ceil(Latency.rtt() / 50.0)), blocks));

		List<BlockPos> cells = new ArrayList<>();
		for (long key : columns) cells.add(BlockPos.of(key));
		place(cells, Rotations.NORMAL);
	}

	@Override
	public String infoString() {
		return target == null ? null : target.getName().getString();
	}
}
