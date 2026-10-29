package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.EnchantmentIds;
import dev.coffeeclient.util.Inv;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.equipment.Equippable;

import java.util.List;

/**
 * Swaps between a chestplate and an elytra. The Best choice scores
 * chestplates by armour, toughness, Protection and durability, and a
 * chestplate with Curse of Binding is never put on. Swap on hit stays on and puts the chestplate
 * on when something hits you in an elytra, then the elytra back once you have gone unhit for a while.
 */
public final class ChestSwap extends Module {
	public enum Chestplate {
		Best, Diamond, Netherite, PreferDiamond, PreferNetherite
	}

	public final EnumSetting<Chestplate> chestplate = group(new EnumSetting<>("Chestplate", "Which chestplate to swap to (Best: highest armour, Protection and durability)", Chestplate.Best), "General");
	public final BooleanSetting stayOn = group(new BooleanSetting("Stay on", "Stay enabled and swap again when turned off", false), "General");
	public final BooleanSetting closeInventory = group(new BooleanSetting("Close inventory", "Send an inventory close after the swap", true), "General");
	public final BooleanSetting onHit = group(new BooleanSetting("Swap on hit", "Stay on: wear the chestplate when hit in an elytra, then the elytra again", false), "On hit");
	public final NumberSetting backAfter = group(new NumberSetting("Swap back after", "Ticks without being hit before the elytra goes back on (0 = never)", 60, 0, 400, 5), "On hit");

	private boolean pendingOff;
	private int lastHurtTime;
	/** Ticks until the elytra goes back on after a hit swap, or -1. */
	private int backIn = -1;

	public ChestSwap() {
		super("ChestSwap", Category.PLAYER, "Automatically swaps between a chestplate and an elytra.");
		backAfter.visibleWhen(onHit::get);
		stayOn.visibleWhen(() -> !onHit.get());
	}

	@Override
	public void onEnable() {
		backIn = -1;
		lastHurtTime = 0;
		if (onHit.get()) return;
		pendingOff = !stayOn.get();
		swap();
	}

	@Override
	public void onDisable() {
		if (stayOn.get() && !onHit.get()) swap();
	}

	@Override
	public void onTick() {
		if (pendingOff) {
			pendingOff = false;
			toggle();
		}
		if (onHit.get()) hitSwap();
	}

	private void hitSwap() {
		var player = mc().player;
		if (player == null) return;
		boolean hit = player.hurtTime > lastHurtTime;
		lastHurtTime = player.hurtTime;
		boolean elytra = player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER);
		if (hit) {
			if (elytra && equipChestplate()) backIn = backAfter.intValue() > 0 ? backAfter.intValue() : -1;
			else if (backIn >= 0) backIn = backAfter.intValue();
			return;
		}
		if (backIn > 0) backIn--;
		else if (backIn == 0) {
			backIn = -1;
			if (!elytra) equipElytra();
		}
	}

	public void swap() {
		if (mc().player == null) return;

		ItemStack current = mc().player.getItemBySlot(EquipmentSlot.CHEST);

		if (current.has(DataComponents.GLIDER)) {
			equipChestplate();
		} else if (current.has(DataComponents.EQUIPPABLE)
			&& current.get(DataComponents.EQUIPPABLE).slot() == EquipmentSlot.CHEST) {
			equipElytra();
		} else if (!equipChestplate()) {
			equipElytra();
		}
	}

	private boolean equipChestplate() {
		var slots = mc().player.getInventory().getNonEquipmentItems();
		int best = chestplate.get() == Chestplate.Best ? bestChestplate(slots) : -1;

		for (int i = 0; i < slots.size() && chestplate.get() != Chestplate.Best; i++) {
			if (!wearable(slots.get(i))) continue;
			Item item = slots.get(i).getItem();
			boolean stop = false;

			switch (chestplate.get()) {
				case Diamond -> {
					if (item == Items.DIAMOND_CHESTPLATE) {
						best = i;
						stop = true;
					}
				}
				case Netherite -> {
					if (item == Items.NETHERITE_CHESTPLATE) {
						best = i;
						stop = true;
					}
				}
				case PreferDiamond -> {
					if (item == Items.DIAMOND_CHESTPLATE) {
						best = i;
						stop = true;
					} else if (item == Items.NETHERITE_CHESTPLATE) {
						best = i;
					}
				}
				case PreferNetherite -> {
					if (item == Items.DIAMOND_CHESTPLATE) {
						best = i;
					} else if (item == Items.NETHERITE_CHESTPLATE) {
						best = i;
						stop = true;
					}
				}
			}
			if (stop) break;
		}

		if (best == -1) return false;
		equip(best);
		return true;
	}

	/** Index of the best-scoring wearable chestplate in {@code stacks}, or -1. */
	public static int bestChestplate(List<ItemStack> stacks) {
		int best = -1;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < stacks.size(); i++) {
			ItemStack stack = stacks.get(i);
			if (!wearable(stack)) continue;
			double score = score(stack);
			if (score > bestScore) {
				bestScore = score;
				best = i;
			}
		}
		return best;
	}

	/** A chestplate (not an elytra) without Curse of Binding, which would lock out the elytra. */
	private static boolean wearable(ItemStack stack) {
		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		if (equippable == null || equippable.slot() != EquipmentSlot.CHEST || stack.has(DataComponents.GLIDER)) return false;
		return !EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
	}

	private static double score(ItemStack stack) {
		ItemAttributeModifiers modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		double armor = modifiers.compute(Attributes.ARMOR, 0, EquipmentSlot.CHEST);
		double toughness = modifiers.compute(Attributes.ARMOR_TOUGHNESS, 0, EquipmentSlot.CHEST);
		int protection = EnchantmentIds.level(stack, Enchantments.PROTECTION);
		double durability = stack.isDamageableItem() ? 1 - stack.getDamageValue() / (double) stack.getMaxDamage() : 1;
		return armor * 5 + toughness * 2 + protection * 3 + durability;
	}

	private void equipElytra() {
		var slots = mc().player.getInventory().getNonEquipmentItems();
		for (int i = 0; i < slots.size(); i++) {
			if (slots.get(i).has(DataComponents.GLIDER)) {
				equip(i);
				break;
			}
		}
	}

	private void equip(int inventorySlot) {
		Inv.move(Inv.inventoryId(inventorySlot), Inv.armorId(EquipmentSlot.CHEST));

		if (closeInventory.get() && mc().getConnection() != null) {
			// Vanilla clients always use window id 0 for the player inventory.
			mc().getConnection().send(new ServerboundContainerClosePacket(0));
		}
	}
}
