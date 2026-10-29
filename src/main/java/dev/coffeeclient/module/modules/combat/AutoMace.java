package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.modules.player.ChestSwap;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EntityTypeListSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.CombatTick;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.Rotations;
import dev.coffeeclient.util.Targets;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.MaceItem;

import java.util.List;

/**
 * Slams with a mace when you fall onto a target: the mace is swapped in for the hit and straight
 * back, once per fall. Targets behind a raised shield, still immune from a hit, or in creative are
 * skipped, and the hit waits for your attack cooldown. Elytra dive swaps your elytra for a
 * chestplate when you dive at a target, so the slam can follow.
 */
public final class AutoMace extends Module {
	/** A smash needs a fall longer than this (MaceItem#canSmashAttack). */
	private static final double SMASH_FALL = 1.5;

	public final NumberSetting minFall = setting(new NumberSetting("Min fall", "Blocks fallen before the slam", 2, 1.5, 20, 0.5));
	public final NumberSetting minCooldown = setting(new NumberSetting("Min cooldown", "How full the attack cooldown must be", 0.9, 0, 1, 0.05));
	public final BooleanSetting rotate = setting(new BooleanSetting("Rotate", "Turn (server side) to the target for the hit", true));
	public final BooleanSetting skipShields = setting(new BooleanSetting("Skip shields", "Leave targets holding up a shield alone", true));
	public final BooleanSetting elytraDive = setting(new BooleanSetting("Elytra dive", "Diving at a target with an elytra, swap it for your best chestplate so the slam can land", false));
	public final NumberSetting diveRange = setting(new NumberSetting("Dive range", "Elytra dive: how close the target must be", 8, 2, 20, 0.5));
	public final EntityTypeListSetting entities = setting(new EntityTypeListSetting("Entities", "Entities to slam", List.of("minecraft:player")));

	private boolean slammed;

	public AutoMace() {
		super("AutoMace", List.of("Auto Mace", "MaceKill"), Category.COMBAT, "Slams a target with your mace when you fall onto it.");
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return entities;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().gameMode == null) return;
		if (player.onGround() || player.isInWater()) slammed = false;
		if (elytraDive.get() && player.isFallFlying() && player.getDeltaMovement().y < 0) dive(player);
		if (slammed || player.fallDistance < Math.max(minFall.get(), SMASH_FALL) || player.isFallFlying()) return;
		if (player.getAttackStrengthScale(0.5f) < minCooldown.get() || CombatTick.busy(CombatTick.Kind.MELEE, this)) return;
		Inv.FindItemResult mace = Inv.findInHotbar(s -> s.getItem() instanceof MaceItem);
		if (!mace.found()) return;
		Entity target = Targets.get(this::valid, Targets.SortPriority.LowestDistance);
		if (target == null) return;
		slammed = true;
		CombatTick.claim(CombatTick.Kind.MELEE, this, 1, 2);
		Runnable hit = () -> Inv.withItem(mace.slot(), true, () -> {
			mc().gameMode.attack(player, target);
			player.swing(InteractionHand.MAIN_HAND);
		});
		if (rotate.get()) Rotations.rotate(Rotations.needed(target), Rotations.HIGH, hit);
		else hit.run();
	}

	/** Elytra dive: a target below in range, so the elytra comes off for the chestplate. */
	private void dive(LocalPlayer player) {
		// Inv's slot ids are only valid while the player's own inventory menu is open.
		if (player.containerMenu != player.inventoryMenu || !player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER)) return;
		boolean target = Targets.get(e -> e instanceof LivingEntity && e != player && entities.has(e) && e.getY() < player.getY()
			&& Targets.withinRange(e, diveRange.get()) && (!(e instanceof Player p) || Targets.isAttackablePlayer(p)), Targets.SortPriority.LowestDistance) != null;
		if (!target) return;
		int best = ChestSwap.bestChestplate(player.getInventory().getNonEquipmentItems());
		if (best >= 0) Inv.move(Inv.inventoryId(best), Inv.armorId(EquipmentSlot.CHEST));
	}

	private boolean valid(Entity entity) {
		LocalPlayer player = mc().player;
		if (!(entity instanceof LivingEntity living) || entity == player || !living.isAlive() || living.isDeadOrDying() || !entities.has(entity)) return false;
		if (!Targets.withinRange(entity, player.entityInteractionRange())) return false;
		// Above half its 20 ticks, the last hit's immunity would swallow the slam.
		if (living.invulnerableTime > 10) return false;
		if (entity instanceof Player p && (!Targets.isAttackablePlayer(p) || skipShields.get() && p.isBlocking())) return false;
		return true;
	}

	@Override
	public String infoString() {
		return mc().player == null ? null : String.format(java.util.Locale.ROOT, "%.1f", mc().player.fallDistance);
	}
}
