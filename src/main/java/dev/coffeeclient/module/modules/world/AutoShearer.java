package dev.coffeeclient.module.modules.world;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.Inv;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.animal.cow.MushroomCow;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.SnowGolem;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;
import net.minecraft.world.entity.monster.skeleton.Bogged;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shears what is in reach with shears from the hotbar: sheep, mooshrooms, snow golems and more, and
 * optionally takes off armour, carpets, saddles, harnesses and leads. Targets and the equipment rules
 * follow ShearAura.
 */
public final class AutoShearer extends Module {
	/** Ticks before the same entity is tried again, so a slow server answer isn't clicked twice. */
	private static final int RETRY_TICKS = 4;

	public final NumberSetting range = setting(new NumberSetting("Range", "Reach to the entity's hitbox", 5, 1, 10, 0.5));

	public final BooleanSetting sheep = group(new BooleanSetting("Sheep", "Shear sheep", true), "Mobs");
	public final BooleanSetting mooshrooms = group(new BooleanSetting("Mooshrooms", "Turn mooshrooms into cows", true), "Mobs");
	public final BooleanSetting snowGolems = group(new BooleanSetting("Snow golems", "Take off their pumpkin", true), "Mobs");
	public final BooleanSetting bogged = group(new BooleanSetting("Bogged", "Shear the mushrooms off bogged", true), "Mobs");
	public final BooleanSetting copperGolems = group(new BooleanSetting("Copper golems", "Shear the flower off copper golems", true), "Mobs");
	public final BooleanSetting sulfurCubes = group(new BooleanSetting("Sulfur cubes", "Shear sulfur cubes", true), "Mobs");

	public final BooleanSetting wolfArmor = group(new BooleanSetting("Wolf armor", "Take armour off your own wolves", false), "Equipment");
	public final BooleanSetting llamaCarpets = group(new BooleanSetting("Llama carpets", "Take carpets off llamas", false), "Equipment");
	public final BooleanSetting harnesses = group(new BooleanSetting("Harnesses", "Take harnesses off happy ghasts", false), "Equipment");
	public final BooleanSetting saddles = group(new BooleanSetting("Saddles", "Take saddles off mounts", false), "Equipment");
	public final BooleanSetting horseArmor = group(new BooleanSetting("Horse armor", "Take armour off horses", false), "Equipment");
	public final BooleanSetting nautilusArmor = group(new BooleanSetting("Nautilus armor", "Take armour off nautiluses", false), "Equipment");
	public final BooleanSetting leashed = group(new BooleanSetting("Leashed mobs", "Cut the lead of leashed mobs", false), "Equipment");
	public final BooleanSetting leashKnots = group(new BooleanSetting("Leash knots", "Cut leads tied to fences", false), "Equipment");

	private final Map<Integer, Long> retryAfter = new HashMap<>();

	public AutoShearer() {
		super("AutoShearer", List.of("ShearAura"), Category.WORLD, "Shears mobs in reach, and can take off their equipment.");
	}

	@Override
	protected void onDisable() {
		retryAfter.clear();
	}

	@Override
	public void onTick() {
		var mc = mc();
		if (mc.player == null || mc.level == null || mc.gameMode == null) return;
		if (mc.gameMode.isDestroying() || mc.player.isHandsBusy()) return;
		Inv.FindItemResult shears = Inv.findInHotbar(stack -> stack.is(Items.SHEARS));
		if (!shears.found()) return;

		long now = mc.level.getGameTime();
		retryAfter.values().removeIf(t -> t <= now);
		Vec3 eye = mc.player.getEyePosition();
		double reach = range.get() * range.get();
		Set<Entity> leashes = new HashSet<>();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e instanceof Leashable leashable && leashable.isLeashed()) {
				leashes.add(e);
				if (leashable.getLeashHolder() != null) leashes.add(leashable.getLeashHolder());
			}
		}
		Entity target = null;
		double best = Double.MAX_VALUE;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e == mc.player || retryAfter.containsKey(e.getId())) continue;
			double d = e.getBoundingBox().distanceToSqr(eye);
			if (d > reach || d >= best || !wanted(e, leashes)) continue;
			best = d;
			target = e;
		}
		if (target == null) return;

		boolean offhand = mc.player.getOffhandItem().is(Items.SHEARS);
		InteractionHand hand = offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
		boolean swapped = !offhand && !mc.player.getMainHandItem().is(Items.SHEARS) && Inv.swap(shears.slot(), true);
		InteractionResult result = mc.gameMode.interact(mc.player, target, new EntityHitResult(target, target.getBoundingBox().getCenter()), hand);
		if (result instanceof InteractionResult.Success) {
			retryAfter.put(target.getId(), now + RETRY_TICKS);
			mc.player.swing(hand, false);
		}
		if (swapped) Inv.swapBack();
	}

	private boolean wanted(Entity e, Set<Entity> leashes) {
		if (leashes.contains(e)) return e instanceof LeashFenceKnotEntity ? leashKnots.get() : leashed.get();
		Boolean equipment = equipment(e);
		if (equipment != null) return equipment;
		if (!(e instanceof Shearable shearable) || !shearable.readyForShearing()) return false;
		if (e instanceof Sheep) return sheep.get();
		if (e instanceof MushroomCow) return mooshrooms.get();
		if (e instanceof SnowGolem) return snowGolems.get();
		if (e instanceof Bogged) return bogged.get();
		if (e instanceof CopperGolem) return copperGolems.get();
		if (e instanceof SulfurCube) return sulfurCubes.get();
		return false;
	}

	/** Whether to shear off a piece of equipment, or null if the mob wears nothing that can be. */
	private Boolean equipment(Entity e) {
		var player = mc().player;
		// Sneaking with shears does nothing to equipment in vanilla.
		if (!(e instanceof Mob mob) || player.isSecondaryUseActive()) return null;
		if (mob instanceof Wolf wolf ? !wolf.isOwnedBy(player) : mob.isVehicle()) return null;
		for (EquipmentSlot slot : EquipmentSlot.VALUES) {
			ItemStack stack = mob.getItemBySlot(slot);
			Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
			if (equippable == null || !equippable.canBeSheared()) continue;
			if (!player.isCreative() && EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) continue;
			if (e instanceof Wolf && stack.is(Items.WOLF_ARMOR)) return wolfArmor.get();
			if (e instanceof Llama && stack.is(ItemTags.WOOL_CARPETS)) return llamaCarpets.get();
			if (e instanceof HappyGhast && stack.is(ItemTags.HARNESSES)) return harnesses.get();
			if (slot == EquipmentSlot.SADDLE && stack.is(Items.SADDLE)) return saddles.get();
			if (slot == EquipmentSlot.BODY && e.typeHolder().is(EntityTypeTags.CAN_WEAR_HORSE_ARMOR)) return horseArmor.get();
			if (slot == EquipmentSlot.BODY && e.typeHolder().is(EntityTypeTags.CAN_WEAR_NAUTILUS_ARMOR)) return nautilusArmor.get();
			return false;
		}
		return null;
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}
}
