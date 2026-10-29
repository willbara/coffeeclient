package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.EnchantmentIds;
import dev.coffeeclient.util.Inv;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.List;

/**
 * Wears the best armour in your inventory, weighting a protection type per slot. Hotbar equip puts
 * hotbar pieces on with a right click instead of an inventory click, which also works mid-fight with
 * a container open. (ChestSwap's key swaps between chestplate and elytra.)
 */
public final class AutoArmor extends Module {
	public enum Protection {
		Protection(Enchantments.PROTECTION),
		Blast(Enchantments.BLAST_PROTECTION),
		Fire(Enchantments.FIRE_PROTECTION),
		Projectile(Enchantments.PROJECTILE_PROTECTION);

		private final ResourceKey<Enchantment> enchantment;

		Protection(ResourceKey<Enchantment> enchantment) {
			this.enchantment = enchantment;
		}
	}

	private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	public final EnumSetting<Protection> helmet = setting(new EnumSetting<>("Helmet protection", "Protection type weighted highest on helmets", Protection.Protection));
	public final EnumSetting<Protection> chestplate = setting(new EnumSetting<>("Chestplate protection", "Protection type weighted highest on chestplates", Protection.Protection));
	public final EnumSetting<Protection> leggings = setting(new EnumSetting<>("Leggings protection", "Protection type weighted highest on leggings", Protection.Blast));
	public final EnumSetting<Protection> boots = setting(new EnumSetting<>("Boots protection", "Protection type weighted highest on boots", Protection.Protection));
	public final NumberSetting delay = setting(new NumberSetting("Swap delay", "Ticks between equipping pieces", 1, 0, 10, 1));
	public final IdListSetting avoided = setting(new IdListSetting("Avoided enchantments", "Never equip pieces with these",
		List.of("minecraft:binding_curse", "minecraft:frost_walker"), EnchantmentIds::all, false).labels(EnchantmentIds::name));
	public final BooleanSetting hotbarEquip = setting(new BooleanSetting("Hotbar equip", "Put on hotbar pieces with a right click (works with a container open)", false));
	public final BooleanSetting antiBreak = setting(new BooleanSetting("Anti break", "Swap pieces that are about to break for a spare, or take them off when there is none", false));
	public final NumberSetting breakThreshold = setting(new NumberSetting("Break threshold", "Uses left at or below which a piece counts as about to break (a spare is worn in its place)", 10, 1, 100, 1));
	public final BooleanSetting ignoreElytra = setting(new BooleanSetting("Ignore elytra", "Never replace a worn elytra", true));

	private int timer;

	public AutoArmor() {
		super("AutoArmor", List.of("Auto Armor"), Category.COMBAT, "Automatically equips the best armor you have.");
		breakThreshold.visibleWhen(antiBreak::get);
	}

	@Override
	protected void onEnable() {
		timer = 0;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().gameMode == null || player.isCreative() || player.isSpectator()) return;
		if (timer > 0) {
			timer--;
			return;
		}

		for (EquipmentSlot slot : SLOTS) {
			// Inv's slot ids are only valid while the player's own inventory menu is the open one.
			if ((player.containerMenu == player.inventoryMenu || hotbarEquip.get()) && equipBest(player, slot)) {
				timer = delay.intValue();
				return;
			}
		}
	}

	/** Swaps in a better piece for {@code slot} (or takes off one about to break); true if it clicked. */
	private boolean equipBest(LocalPlayer player, EquipmentSlot slot) {
		ItemStack worn = player.getItemBySlot(slot);
		if (ignoreElytra.get() && worn.is(Items.ELYTRA)) return false;
		if (EnchantmentIds.level(worn, Enchantments.BINDING_CURSE) > 0) return false;

		int bestIndex = -1;
		int bestScore = -1;
		for (int i = 0; i < 36; i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (!isArmor(stack) || slotOf(stack) != slot || hasAvoided(stack) || aboutToBreak(stack)) continue;
			int score = score(player, stack, slot);
			if (score > bestScore) {
				bestScore = score;
				bestIndex = i;
			}
		}

		int wornScore = worn.isEmpty() ? -1 : aboutToBreak(worn) ? -1 : score(player, worn, slot) - avoidedPenalty(worn);
		if (bestIndex >= 0 && bestScore > wornScore && hotbarEquip.get() && bestIndex < 9 && !player.isUsingItem()) {
			// A right click on worn armour swaps it with what is worn.
			return Inv.withItem(bestIndex, true, () -> mc().gameMode.useItem(player, InteractionHand.MAIN_HAND));
		}
		if (player.containerMenu != player.inventoryMenu) return false;
		if (bestIndex >= 0 && bestScore > wornScore) {
			Inv.move(Inv.inventoryId(bestIndex), Inv.armorId(slot));
			return true;
		}
		if (!worn.isEmpty() && aboutToBreak(worn)) {
			int empty = Inv.findEmptySlot();
			if (empty >= 0) {
				Inv.move(Inv.armorId(slot), Inv.inventoryId(empty));
				return true;
			}
		}
		return false;
	}

	private int score(LocalPlayer player, ItemStack stack, EquipmentSlot slot) {
		ResourceKey<Enchantment> favoured = (switch (slot) {
			case HEAD -> helmet;
			case CHEST -> chestplate;
			case LEGS -> leggings;
			default -> boots;
		}).get().enchantment;
		int score = 3 * EnchantmentIds.level(stack, favoured);
		score += EnchantmentIds.level(stack, Enchantments.PROTECTION);
		score += EnchantmentIds.level(stack, Enchantments.BLAST_PROTECTION);
		score += EnchantmentIds.level(stack, Enchantments.FIRE_PROTECTION);
		score += EnchantmentIds.level(stack, Enchantments.PROJECTILE_PROTECTION);
		score += EnchantmentIds.level(stack, Enchantments.UNBREAKING);
		score += 2 * EnchantmentIds.level(stack, Enchantments.MENDING);

		ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
		if (modifiers != null) {
			for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
				if (entry.attribute() != Attributes.ARMOR && entry.attribute() != Attributes.ARMOR_TOUGHNESS) continue;
				double amount = entry.modifier().amount();
				score += (int) switch (entry.modifier().operation()) {
					case ADD_VALUE -> amount;
					case ADD_MULTIPLIED_BASE -> amount * player.getAttributeBaseValue(entry.attribute());
					case ADD_MULTIPLIED_TOTAL -> amount * score;
				};
			}
		}
		return score;
	}

	private boolean hasAvoided(ItemStack stack) {
		for (Object2IntMap.Entry<Holder<Enchantment>> entry : stack.getEnchantments().entrySet()) {
			if (entry.getKey().unwrapKey().map(key -> avoided.contains(key.identifier())).orElse(false)) return true;
		}
		return false;
	}

	/** Worn pieces with avoided enchantments lose out to any clean replacement. */
	private int avoidedPenalty(ItemStack stack) {
		int penalty = 0;
		for (Object2IntMap.Entry<Holder<Enchantment>> entry : stack.getEnchantments().entrySet()) {
			if (entry.getKey().unwrapKey().map(key -> avoided.contains(key.identifier())).orElse(false)) penalty += 2 * entry.getIntValue();
		}
		return penalty;
	}

	private boolean aboutToBreak(ItemStack stack) {
		return antiBreak.get() && stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= breakThreshold.get();
	}

	private static boolean isArmor(ItemStack stack) {
		return !stack.isEmpty() && (stack.is(ItemTags.FOOT_ARMOR) || stack.is(ItemTags.LEG_ARMOR)
			|| stack.is(ItemTags.CHEST_ARMOR) || stack.is(ItemTags.HEAD_ARMOR));
	}

	private static EquipmentSlot slotOf(ItemStack stack) {
		var equippable = stack.get(DataComponents.EQUIPPABLE);
		return equippable == null ? null : equippable.slot();
	}
}
