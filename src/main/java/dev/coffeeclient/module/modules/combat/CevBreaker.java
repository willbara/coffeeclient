package dev.coffeeclient.module.modules.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.WorldOverlays;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.BlockActions;
import dev.coffeeclient.util.CombatTick;
import dev.coffeeclient.util.CrystalTracker;
import dev.coffeeclient.util.Damage;
import dev.coffeeclient.util.Footprint;
import dev.coffeeclient.util.Holes;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.MiningManager;
import dev.coffeeclient.util.PacketBatch;
import dev.coffeeclient.util.Rotations;
import dev.coffeeclient.util.Targets;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Crystals an enemy hiding in a hole through the one cell they can't cover from inside: obsidian
 * goes above their head (Cev) or beside it (Civ), a crystal on it, then the obsidian is mined and the
 * crystal, now with nothing in between, is set off. The chosen cell is kept until it stops working,
 * and each round runs on what the cell holds: air gets obsidian, bare obsidian a crystal, obsidian
 * under a crystal is mined, a floating crystal is hit. Instant rebreaks the cell from the second
 * round on, with no new mining time.
 */
public final class CevBreaker extends Module {
	public enum Spot { Head, Side, Both }

	public final NumberSetting targetRange = setting(new NumberSetting("Target range", "Farthest enemy to fight", 5.5, 1, 8, 0.5));
	public final EnumSetting<Spot> spot = setting(new EnumSetting<>("Spot", "Head puts the obsidian above their head (cev), Side beside their head (civ)", Spot.Both));
	public final BooleanSetting instant = setting(new BooleanSetting("Instant", "Break the cell again at once from the second round on (needs a server that allows instant rebreak)", true));
	public final NumberSetting minDamage = setting(new NumberSetting("Min damage", "Least damage to the enemy worth a round", 6, 0, 36, 0.5));
	public final NumberSetting maxSelf = setting(new NumberSetting("Max self damage", "Most damage you will take from the crystal", 8, 0, 36, 0.5));
	public final BooleanSetting rotate = setting(new BooleanSetting("Rotate", "Face what you place or hit", true));
	public final BooleanSetting swing = setting(new BooleanSetting("Swing", "Swing your hand", true));
	public final BooleanSetting render = setting(new BooleanSetting("Render", "Box the cell in use", true));
	public final ColorSetting color = setting(new ColorSetting("Color", "Box colour", 0xFFB040FF));

	private final MiningManager mining = new MiningManager();
	private Player target;
	private BlockPos base;
	/** The base was broken once by us, so the server allows rebreaking it at once. */
	private boolean broken;

	public CevBreaker() {
		super("CevBreaker", List.of("AutoCev", "Cev", "Civ"), Category.COMBAT, "Crystals enemies in holes through the block above or beside their head.");
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
		base = null;
		broken = false;
		CombatTick.release(this);
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		ClientLevel level = mc().level;
		if (player == null || level == null || player.isUsingItem() || CombatTick.lagging()) return;
		target = Targets.playerTarget(targetRange.get(), Targets.SortPriority.LowestDistance);
		if (target == null || !Inv.findInHotbar(s -> s.is(Items.END_CRYSTAL)).found()) {
			lose();
			return;
		}
		List<BlockPos> spots = spots(level);
		if (base != null && !spots.contains(base)) lose();
		if (base == null) base = best(player, level, spots);
		if (base == null) return;
		CombatTick.claim(CombatTick.Kind.CRYSTAL, this, 0, 2);

		BlockState state = level.getBlockState(base);
		EndCrystal crystal = crystalOn(level, base);
		if (state.canBeReplaced()) {
			if (crystal != null) hit(player, crystal);
			else placeObsidian();
		} else if (state.is(Blocks.OBSIDIAN)) {
			if (crystal == null) placeCrystal();
			else mine(player, state);
		} else {
			lose();
		}
	}

	private void lose() {
		mining.abort();
		base = null;
		broken = false;
	}

	/** The cells this enemy can be reached through, by Spot: above the head, then beside it. */
	private List<BlockPos> spots(ClientLevel level) {
		Holes.Blocks blocks = Footprint.blocks(level);
		LongSet columns = Footprint.columns(target.getBoundingBox(), blocks);
		List<BlockPos> spots = new ArrayList<>();
		if (spot.get() != Spot.Side) for (long key : columns) spots.add(BlockPos.of(key).above(2));
		if (spot.get() != Spot.Head) for (BlockPos wall : Footprint.ring(columns)) spots.add(wall.above());
		return spots;
	}

	/** The spot whose crystal hurts the enemy most within your limits, or null. */
	private BlockPos best(LocalPlayer player, ClientLevel level, List<BlockPos> spots) {
		double reach = player.blockInteractionRange();
		float health = Damage.health(player);
		BlockPos best = null;
		float bestDamage = 0;
		for (BlockPos pos : spots) {
			BlockState state = level.getBlockState(pos);
			if (!state.canBeReplaced() && !state.is(Blocks.OBSIDIAN)) continue;
			if (!level.getBlockState(pos.above()).isAir() || !level.getBlockState(pos.above(2)).isAir()) continue;
			if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > reach * reach) continue;
			if (state.canBeReplaced() && BlockActions.placeParams(pos) == null) continue;
			// Rated as it goes off: the obsidian mined away, the crystal on top of where it was.
			Damage.View view = new Damage.View(level, pos, Blocks.AIR.defaultBlockState());
			Vec3 at = CrystalTracker.center(pos);
			float self = Damage.mitigate(player, Damage.crystalRaw(player, player.position(), player.getBoundingBox(), at, view));
			if (self > maxSelf.get() || self >= health) continue;
			float damage = Damage.mitigate(target, Damage.crystalRaw(target, target.position(), target.getBoundingBox(), at, view));
			if (damage >= minDamage.get() && damage > bestDamage) {
				best = pos.immutable();
				bestDamage = damage;
			}
		}
		return best;
	}

	private void placeObsidian() {
		Inv.FindItemResult obsidian = Inv.findInHotbar(s -> s.is(Items.OBSIDIAN));
		if (obsidian.found()) BlockActions.place(base, obsidian, rotate.get(), Rotations.HIGH, swing.get(), true, true);
	}

	private void placeCrystal() {
		Inv.FindItemResult crystals = Inv.findInHotbar(s -> s.is(Items.END_CRYSTAL));
		BlockHitResult top = new BlockHitResult(Vec3.atCenterOf(base).add(0, 0.5, 0), Direction.UP, base, false);
		if (crystals.found()) BlockActions.placeAt(top, crystals, rotate.get(), Rotations.HIGH, swing.get(), true);
	}

	private void mine(LocalPlayer player, BlockState state) {
		int tool = Inv.bestTool(state);
		if (broken && instant.get()) {
			Inv.withItem(tool, true, mining::rebreak);
			return;
		}
		if (!base.equals(mining.target())) {
			BlockActions.BreakParams face = BlockActions.breakParams(player.getEyePosition(), base);
			if (face == null) return;
			mining.start(base, face.side());
		}
		if (mining.tick(player, tool, 1)) {
			mining.finish(tool, true);
			broken = true;
		}
	}

	private void hit(LocalPlayer player, EndCrystal crystal) {
		if (Damage.crystal(player, crystal.position()) >= Damage.health(player)) return;
		Runnable attack = () -> PacketBatch.run(() -> {
			player.connection.send(new ServerboundAttackPacket(crystal.getId()));
			if (swing.get()) player.swing(InteractionHand.MAIN_HAND);
		});
		if (rotate.get()) {
			if (!Rotations.rotate(crystal.position(), Rotations.HIGH, attack)) return;
		} else {
			attack.run();
		}
		CrystalTracker.explodeAt(crystal.position());
	}

	/** A live crystal sitting on {@code base}. */
	private static EndCrystal crystalOn(ClientLevel level, BlockPos base) {
		for (EndCrystal crystal : level.getEntitiesOfClass(EndCrystal.class, new AABB(base.above()))) {
			if (crystal.blockPosition().equals(base.above()) && CrystalTracker.isAlive(crystal)) return crystal;
		}
		return null;
	}

	@Override
	public void renderWorld(PoseStack pose, Vec3 cam) {
		if (!render.get() || base == null) return;
		EspRender.boxes(List.of(new AABB(base)), new int[] {color.argb() & 0xFFFFFF}, WorldOverlays.Shape.Both, false, 1.5f, (color.argb() >>> 24) / 3, cam);
	}

	@Override
	public String infoString() {
		return target == null ? null : target.getName().getString();
	}
}
