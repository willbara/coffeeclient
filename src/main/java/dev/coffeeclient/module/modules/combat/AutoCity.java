package dev.coffeeclient.module.modules.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.BlockActions;
import dev.coffeeclient.util.CrystalTracker;
import dev.coffeeclient.util.Damage;
import dev.coffeeclient.util.Footprint;
import dev.coffeeclient.util.Holes;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.MiningManager;
import dev.coffeeclient.util.Rotations;
import dev.coffeeclient.util.Targets;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Breaks an enemy's surround (or the block they burrowed into) by packets, picking the wall whose
 * gap lets a crystal hurt them most, and breaks it again whenever they replace it. With Support, a
 * wall with no crystal base under it gets an obsidian one first. Double mine breaks the best two walls
 * at once: the first is handed to the server to finish by itself while the second is mined.
 */
public final class AutoCity extends Module {
	/** A break this far along keeps going even if a slightly better wall turns up. */
	private static final double KEEP_PROGRESS = 0.3;
	/** A handed-off break that hasn't finished after this many ticks is forgotten. */
	private static final int PARK_TIMEOUT = 100;

	public final NumberSetting targetRange = setting(new NumberSetting("Target range", "Farthest enemy to city", 5.5, 1, 8, 0.5));
	public final BooleanSetting burrow = setting(new BooleanSetting("Burrow", "Break the block an enemy burrowed into first", true));
	public final BooleanSetting rebreak = setting(new BooleanSetting("Rebreak", "Break the wall again whenever it is replaced", true));
	public final NumberSetting minDamage = setting(new NumberSetting("Min damage", "Least crystal damage the gap must allow", 6, 0, 36, 0.5));
	public final NumberSetting maxSelf = setting(new NumberSetting("Max self damage", "Skip walls whose crystal would hurt you more than this", 8, 0, 36, 0.5));
	public final BooleanSetting support = setting(new BooleanSetting("Support", "Place obsidian under a wall that has no crystal base", false));
	public final BooleanSetting doubleMine = setting(new BooleanSetting("Double mine", "Break the best two walls at once; holds your best tool for it, since the server counts the first break with what you hold", false));
	public final BooleanSetting needCrystals = setting(new BooleanSetting("Need crystals", "Only city while you have end crystals in your hotbar", true));
	public final BooleanSetting render = setting(new BooleanSetting("Render", "A box that grows with the break", true));
	public final ColorSetting color = setting(new ColorSetting("Color", "Box colour", 0xFFFF5050));

	private final MiningManager mining = new MiningManager();
	private Player target;
	private BlockPos current;
	/** A wall handed to the server to finish by itself (Double mine), and when. */
	private BlockPos parked;
	private int parkedAt;

	public AutoCity() {
		super("AutoCity", List.of("Auto City", "City"), Category.COMBAT, "Breaks an enemy's surround so crystals can reach them.");
		color.visibleWhen(render::get);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void onDisable() {
		mining.abort();
		target = null;
		current = parked = null;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		ClientLevel level = mc().level;
		if (player == null || level == null) return;
		target = Targets.playerTarget(targetRange.get(), Targets.SortPriority.LowestDistance);
		if (target == null || needCrystals.get() && !Inv.findInHotbar(s -> s.is(Items.END_CRYSTAL)).found()) {
			stop();
			return;
		}
		Holes.Blocks blocks = Footprint.blocks(level);
		LongSet columns = Footprint.columns(target.getBoundingBox(), blocks);
		if (current != null && !stillPart(current, columns)) stop();
		if (parked != null && (level.getBlockState(parked).canBeReplaced() || player.tickCount - parkedAt > PARK_TIMEOUT || !stillPart(parked, columns))) parked = null;
		if (parked != null) Inv.swap(Inv.bestTool(level.getBlockState(parked)), false);

		List<Choice> choices = choose(player, level, columns, blocks);
		Choice best = choices.isEmpty() ? null : choices.getFirst();
		if (best != null && (current == null || !best.pos.equals(current) && mining.progress() < KEEP_PROGRESS)) {
			mining.abort();
			current = best.pos;
		}
		if (current == null) return;
		if (support.get() && needsSupport(level, current)) {
			placeSupport(current.below());
			return;
		}

		BlockState state = level.getBlockState(current);
		if (state.canBeReplaced()) {
			// Broken: wait for a replacement (Rebreak) or move on.
			mining.clear();
			if (!rebreak.get()) current = null;
			return;
		}
		if (!current.equals(mining.target())) {
			BlockActions.BreakParams face = BlockActions.breakParams(player.getEyePosition(), current);
			if (face == null) return;
			mining.start(current, face.side());
		}
		int tool = Inv.bestTool(state);
		if (mining.tick(player, tool, 1)) mining.finish(tool, true);
		// Once the server has had a tick of this break, hand it off and start on the next wall.
		if (doubleMine.get() && parked == null && mining.progress() > 0 && mining.progress() < 1) {
			for (Choice next : choices) {
				if (next.pos.equals(current) || needsSupport(level, next.pos)) continue;
				parked = current;
				parkedAt = player.tickCount;
				mining.handOff();
				current = next.pos;
				break;
			}
		}
	}

	private void stop() {
		mining.abort();
		current = null;
	}

	private record Choice(BlockPos pos, float score) {
	}

	/** The walls (or burrow block) worth breaking, best first; the handed-off one is left out. */
	private List<Choice> choose(LocalPlayer player, ClientLevel level, LongSet columns, Holes.Blocks blocks) {
		List<Choice> choices = new ArrayList<>();
		if (burrow.get()) {
			for (long key : columns) {
				BlockPos pos = BlockPos.of(key);
				if (!pos.equals(parked) && minable(player, level, pos) && target.getBoundingBox().intersects(new AABB(pos))) choices.add(new Choice(pos, Float.MAX_VALUE));
			}
		}
		List<BlockPos> own = Footprint.ring(Footprint.columns(player.getBoundingBox(), blocks));
		for (BlockPos wall : Footprint.ring(columns)) {
			if (wall.equals(parked) || own.contains(wall) || !minable(player, level, wall) && !wall.equals(current)) continue;
			if (needsSupport(level, wall) && (!support.get() || !level.getBlockState(wall.below()).canBeReplaced())) continue;
			// Rate a crystal in the gap: the wall gone, a base under it.
			BlockPos base = wall.below();
			Damage.View view = new Damage.View(level, wall, Blocks.AIR.defaultBlockState());
			Vec3 crystal = CrystalTracker.center(base);
			float self = Damage.mitigate(player, Damage.crystalRaw(player, player.position(), player.getBoundingBox(), crystal, view));
			if (self > maxSelf.get() || self >= Damage.health(player)) continue;
			float damage = Damage.mitigate(target, Damage.crystalRaw(target, target.position(), target.getBoundingBox(), crystal, view));
			if (damage >= minDamage.get()) choices.add(new Choice(wall, damage));
		}
		choices.sort((a, b) -> Float.compare(b.score, a.score));
		return choices;
	}

	private boolean stillPart(BlockPos pos, LongSet columns) {
		return columns.contains(pos.asLong()) || Footprint.ring(columns).contains(pos);
	}

	private static boolean minable(LocalPlayer player, ClientLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		double reach = player.blockInteractionRange();
		return !state.canBeReplaced() && state.getDestroySpeed(level, pos) >= 0 && player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= reach * reach;
	}

	/** No crystal base (obsidian or bedrock) under the wall. */
	private static boolean needsSupport(ClientLevel level, BlockPos wall) {
		BlockState base = level.getBlockState(wall.below());
		return !base.is(Blocks.OBSIDIAN) && !base.is(Blocks.BEDROCK);
	}

	private void placeSupport(BlockPos base) {
		Inv.FindItemResult obsidian = Inv.findInHotbar(s -> s.is(Items.OBSIDIAN));
		if (obsidian.found()) BlockActions.place(base, obsidian, true, Rotations.NORMAL, true, true, true);
	}

	@Override
	public void renderWorld(PoseStack pose, Vec3 cam) {
		BlockPos at = mining.target();
		if (!render.get() || at == null) return;
		double half = 0.5 * Math.min(1, mining.progress());
		Vec3 c = Vec3.atCenterOf(at);
		int rgb = color.argb() & 0xFFFFFF;
		EspRender.boxes(List.of(new AABB(at)), new int[] {rgb}, WorldOverlays.Shape.Outline, true, 1.5f, 0, cam);
		EspRender.boxes(List.of(new AABB(c.x - half, c.y - half, c.z - half, c.x + half, c.y + half, c.z + half)), new int[] {rgb},
			WorldOverlays.Shape.Filled, true, 1, 90, cam);
	}

	@Override
	public String infoString() {
		return target == null ? null : target.getName().getString();
	}
}
