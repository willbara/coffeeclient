package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.BlockActions;
import dev.coffeeclient.util.CombatTick;
import dev.coffeeclient.util.CrystalTracker;
import dev.coffeeclient.util.Damage;
import dev.coffeeclient.util.Footprint;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.MotionTracker;
import dev.coffeeclient.util.PacketBatch;
import dev.coffeeclient.util.Rotations;
import dev.coffeeclient.util.Targets;
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

import java.util.List;

/**
 * Double tap: when an enemy is sent flying (by your hit or anything else), obsidian goes beside
 * where they will land, and two crystals go off there one hurt-immunity apart, so both hits count
 * for a quick double totem pop.
 */
public final class DTap extends Module {
	public final NumberSetting targetRange = setting(new NumberSetting("Target range", "Farthest enemy to tap", 6, 2, 8, 0.5));
	public final NumberSetting minSpeed = setting(new NumberSetting("Min speed", "How fast (blocks per tick) the enemy must be knocked to start", 0.3, 0.1, 2, 0.05));
	public final NumberSetting predict = setting(new NumberSetting("Predict ticks", "How far ahead to aim for where they land", 4, 1, 10, 1));
	public final NumberSetting gap = setting(new NumberSetting("Gap", "Ticks between the two crystals (10 is a full hurt immunity)", 10, 1, 20, 1));
	public final NumberSetting minDamage = setting(new NumberSetting("Min damage", "Least damage a crystal must deal", 6, 0, 36, 0.5));
	public final NumberSetting maxSelf = setting(new NumberSetting("Max self damage", "Most damage you will take from one crystal", 8, 0, 36, 0.5));

	private Player target;
	/** The base both crystals go on, the crystals set off so far, and the tick the first one went. */
	private BlockPos base;
	private int fired, firedAt;
	/** No new tap before this tick: our own crystals knock the enemy too, and must not start another. */
	private int readyAt;

	public DTap() {
		super("DTap", List.of("D-Tap", "Double Tap"), Category.COMBAT, "Two crystals one hurt-immunity apart where a knocked enemy lands.");
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	protected void onDisable() {
		reset();
		target = null;
		CombatTick.release(this);
	}

	private void reset() {
		base = null;
		fired = 0;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		ClientLevel level = mc().level;
		if (player == null || level == null || CombatTick.lagging()) return;
		if (base == null) {
			if (player.tickCount < readyAt) return;
			target = Targets.playerTarget(targetRange.get(), Targets.SortPriority.LowestDistance);
			if (target == null || target.hurtTime == 0 || target.getDeltaMovement().horizontalDistance() < minSpeed.get()) return;
			base = pickBase(player, level);
			if (base == null) return;
		}
		if (target == null || !target.isAlive() || fired >= 2 || fired == 1 && player.tickCount - firedAt > gap.intValue() * 3) {
			if (fired > 0) readyAt = player.tickCount + gap.intValue() * 2;
			reset();
			return;
		}
		CombatTick.claim(CombatTick.Kind.CRYSTAL, this, 0, 2);

		EndCrystal crystal = crystalOn(level, base);
		if (crystal != null) {
			// The second one waits out the hurt immunity the first one started.
			if (fired == 0 || player.tickCount - firedAt >= gap.intValue()) hit(player, crystal);
		} else if (!level.getBlockState(base).is(Blocks.OBSIDIAN) && !level.getBlockState(base).is(Blocks.BEDROCK)) {
			Inv.FindItemResult obsidian = Inv.findInHotbar(s -> s.is(Items.OBSIDIAN));
			if (!obsidian.found() || !BlockActions.place(base, obsidian, true, Rotations.HIGH, true, true, true)) reset();
		} else {
			Inv.FindItemResult crystals = Inv.findInHotbar(s -> s.is(Items.END_CRYSTAL));
			if (!crystals.found()) {
				reset();
				return;
			}
			BlockHitResult top = new BlockHitResult(Vec3.atCenterOf(base).add(0, 0.5, 0), Direction.UP, base, false);
			BlockActions.placeAt(top, crystals, true, Rotations.HIGH, true, true);
		}
	}

	/** Under a cell beside where the target lands: the base whose crystal hurts them most within your limits. */
	private BlockPos pickBase(LocalPlayer player, ClientLevel level) {
		AABB landing = MotionTracker.predict(target, predict.intValue());
		double reach = player.blockInteractionRange();
		BlockPos best = null;
		float bestDamage = 0;
		for (BlockPos cell : Footprint.ring(Footprint.columns(landing, Footprint.blocks(level)))) {
			BlockPos below = cell.below();
			BlockState state = level.getBlockState(below);
			boolean isBase = state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
			if (!isBase && (!BlockActions.canPlace(below, Blocks.OBSIDIAN, true) || BlockActions.placeParams(below) == null)) continue;
			if (!level.getBlockState(cell).isAir() || !level.getBlockState(cell.above()).isAir()) continue;
			if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(below)) > reach * reach) continue;
			Damage.View view = Damage.View.obsidian(level, below);
			Vec3 at = CrystalTracker.center(below);
			float self = Damage.mitigate(player, Damage.crystalRaw(player, player.position(), player.getBoundingBox(), at, view));
			if (self > maxSelf.get() || self >= Damage.health(player)) continue;
			float damage = Damage.mitigate(target, Damage.crystalRaw(target, MotionTracker.feet(landing), landing, at, view));
			if (damage >= minDamage.get() && damage > bestDamage) {
				best = below.immutable();
				bestDamage = damage;
			}
		}
		return best;
	}

	private void hit(LocalPlayer player, EndCrystal crystal) {
		if (Damage.crystal(player, crystal.position()) >= Damage.health(player)) {
			reset();
			return;
		}
		boolean sent = Rotations.rotate(crystal.position(), Rotations.HIGH, () -> PacketBatch.run(() -> {
			player.connection.send(new ServerboundAttackPacket(crystal.getId()));
			player.swing(InteractionHand.MAIN_HAND);
		}));
		if (!sent) return;
		CrystalTracker.explodeAt(crystal.position());
		if (fired++ == 0) firedAt = player.tickCount;
	}

	private static EndCrystal crystalOn(ClientLevel level, BlockPos base) {
		for (EndCrystal crystal : level.getEntitiesOfClass(EndCrystal.class, new AABB(base.above()))) {
			if (CrystalTracker.isAlive(crystal)) return crystal;
		}
		return null;
	}

	@Override
	public String infoString() {
		return base == null ? null : fired + "/2";
	}
}
