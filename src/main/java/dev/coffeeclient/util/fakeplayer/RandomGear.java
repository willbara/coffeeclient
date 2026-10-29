package dev.coffeeclient.util.fakeplayer;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/** A random high-end kit for a bot: diamond or netherite armor (sometimes an elytra or turtle helmet), a weapon or tool, and an offhand, all enchanted. */
final class RandomGear {
	private final LivingEntity bot;
	private final Random random = ThreadLocalRandom.current();

	private RandomGear(LivingEntity bot) {
		this.bot = bot;
	}

	static void equip(LivingEntity bot) {
		new RandomGear(bot).equip();
	}

	private void equip() {
		boolean netherite = random.nextBoolean();
		bot.setItemSlot(EquipmentSlot.HEAD, helmet(netherite));
		bot.setItemSlot(EquipmentSlot.CHEST, random.nextInt(100) < 30 ? elytra() : armor(netherite ? Items.NETHERITE_CHESTPLATE : Items.DIAMOND_CHESTPLATE, 0.4, Enchantments.THORNS));
		bot.setItemSlot(EquipmentSlot.LEGS, armor(netherite ? Items.NETHERITE_LEGGINGS : Items.DIAMOND_LEGGINGS, 0.5, Enchantments.SWIFT_SNEAK));
		bot.setItemSlot(EquipmentSlot.FEET, boots(netherite));
		bot.setItemSlot(EquipmentSlot.MAINHAND, mainHand());
		bot.setItemSlot(EquipmentSlot.OFFHAND, offHand());
	}

	private ItemStack helmet(boolean netherite) {
		if (random.nextInt(100) < 25) {
			ItemStack turtle = armor(Items.TURTLE_HELMET, 0.6, Enchantments.RESPIRATION);
			maybe(turtle, 0.6, Enchantments.AQUA_AFFINITY);
			return turtle;
		}
		ItemStack helmet = armor(netherite ? Items.NETHERITE_HELMET : Items.DIAMOND_HELMET, 0.6, Enchantments.RESPIRATION);
		maybe(helmet, 0.6, Enchantments.AQUA_AFFINITY);
		return helmet;
	}

	private ItemStack boots(boolean netherite) {
		ItemStack boots = armor(netherite ? Items.NETHERITE_BOOTS : Items.DIAMOND_BOOTS, 0.9, Enchantments.FEATHER_FALLING);
		maybe(boots, 0.5, Enchantments.DEPTH_STRIDER, Enchantments.FROST_WALKER);
		maybe(boots, 0.4, Enchantments.SOUL_SPEED);
		return boots;
	}

	/** An armor piece with one random protection type, a slot-specific extra, unbreaking and mending. */
	@SafeVarargs
	private ItemStack armor(Item item, double extraChance, ResourceKey<Enchantment>... extra) {
		ItemStack stack = new ItemStack(item);
		maybe(stack, 1.0, Enchantments.PROTECTION, Enchantments.PROTECTION, Enchantments.BLAST_PROTECTION, Enchantments.FIRE_PROTECTION, Enchantments.PROJECTILE_PROTECTION);
		maybe(stack, extraChance, extra);
		durable(stack);
		return stack;
	}

	private ItemStack elytra() {
		ItemStack stack = new ItemStack(Items.ELYTRA);
		durable(stack);
		return stack;
	}

	private ItemStack mainHand() {
		return switch (random.nextInt(9)) {
			case 0 -> sword(random.nextBoolean() ? Items.NETHERITE_SWORD : Items.DIAMOND_SWORD);
			case 1 -> axe(random.nextBoolean() ? Items.NETHERITE_AXE : Items.DIAMOND_AXE);
			case 2 -> tool(random.nextBoolean() ? Items.NETHERITE_PICKAXE : Items.DIAMOND_PICKAXE);
			case 3 -> tool(random.nextBoolean() ? Items.NETHERITE_SHOVEL : Items.DIAMOND_SHOVEL);
			case 4 -> mace();
			case 5 -> trident();
			case 6 -> bow();
			case 7 -> crossbow();
			default -> sword(Items.NETHERITE_SWORD);
		};
	}

	private ItemStack offHand() {
		return switch (random.nextInt(4)) {
			case 0, 1 -> new ItemStack(Items.TOTEM_OF_UNDYING);
			case 2 -> {
				ItemStack shield = new ItemStack(Items.SHIELD);
				durable(shield);
				yield shield;
			}
			default -> new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1 + random.nextInt(8));
		};
	}

	private ItemStack sword(Item item) {
		ItemStack stack = new ItemStack(item);
		maybe(stack, 1.0, Enchantments.SHARPNESS, Enchantments.SHARPNESS, Enchantments.SMITE, Enchantments.BANE_OF_ARTHROPODS);
		maybe(stack, 0.4, Enchantments.KNOCKBACK);
		maybe(stack, 0.6, Enchantments.FIRE_ASPECT);
		maybe(stack, 0.6, Enchantments.LOOTING);
		maybe(stack, 0.7, Enchantments.SWEEPING_EDGE);
		durable(stack);
		return stack;
	}

	private ItemStack axe(Item item) {
		ItemStack stack = new ItemStack(item);
		maybe(stack, 1.0, Enchantments.SHARPNESS, Enchantments.SHARPNESS, Enchantments.SMITE, Enchantments.BANE_OF_ARTHROPODS);
		maybe(stack, 0.8, Enchantments.EFFICIENCY);
		durable(stack);
		return stack;
	}

	private ItemStack tool(Item item) {
		ItemStack stack = new ItemStack(item);
		maybe(stack, 0.9, Enchantments.EFFICIENCY);
		maybe(stack, 0.7, Enchantments.FORTUNE, Enchantments.SILK_TOUCH);
		durable(stack);
		return stack;
	}

	private ItemStack mace() {
		ItemStack stack = new ItemStack(Items.MACE);
		maybe(stack, 0.9, Enchantments.DENSITY, Enchantments.BREACH);
		maybe(stack, 0.5, Enchantments.WIND_BURST);
		maybe(stack, 0.4, Enchantments.FIRE_ASPECT);
		durable(stack);
		return stack;
	}

	private ItemStack trident() {
		ItemStack stack = new ItemStack(Items.TRIDENT);
		maybe(stack, 0.8, Enchantments.IMPALING);
		// Riptide excludes loyalty and channeling.
		if (random.nextBoolean()) {
			maybe(stack, 1.0, Enchantments.LOYALTY);
			maybe(stack, 0.4, Enchantments.CHANNELING);
		} else {
			maybe(stack, 1.0, Enchantments.RIPTIDE);
		}
		durable(stack);
		return stack;
	}

	private ItemStack bow() {
		ItemStack stack = new ItemStack(Items.BOW);
		maybe(stack, 1.0, Enchantments.POWER);
		maybe(stack, 0.4, Enchantments.PUNCH);
		maybe(stack, 0.5, Enchantments.FLAME);
		// Infinity excludes mending, so it takes mending's place when rolled.
		if (random.nextInt(100) < 35) {
			enchant(stack, Enchantments.INFINITY);
			maybe(stack, 0.9, Enchantments.UNBREAKING);
		} else {
			durable(stack);
		}
		return stack;
	}

	private ItemStack crossbow() {
		ItemStack stack = new ItemStack(Items.CROSSBOW);
		maybe(stack, 0.9, Enchantments.QUICK_CHARGE);
		maybe(stack, 0.9, Enchantments.MULTISHOT, Enchantments.PIERCING);
		durable(stack);
		return stack;
	}

	private void durable(ItemStack stack) {
		maybe(stack, 0.9, Enchantments.UNBREAKING);
		maybe(stack, 0.8, Enchantments.MENDING);
	}

	/** With the given chance, enchants with one of {@code options}, picked at random, at a mostly maxed level. */
	@SafeVarargs
	private void maybe(ItemStack stack, double chance, ResourceKey<Enchantment>... options) {
		if (options.length == 0 || random.nextDouble() >= chance) return;
		enchant(stack, options[random.nextInt(options.length)]);
	}

	private void enchant(ItemStack stack, ResourceKey<Enchantment> key) {
		Holder<Enchantment> enchantment = bot.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
		int max = enchantment.value().getMaxLevel();
		stack.enchant(enchantment, random.nextInt(100) < 70 ? max : 1 + random.nextInt(max));
	}
}
