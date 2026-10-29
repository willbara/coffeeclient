package dev.coffeeclient.util;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.List;

/** Enchantment ids and levels. Enchantments are a dynamic registry, so the list needs a loaded world. */
public final class EnchantmentIds {
	private EnchantmentIds() {
	}

	/** Every enchantment id in the current world, or a few common ones when no world is loaded. */
	public static List<String> all() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return List.of("minecraft:protection", "minecraft:sharpness", "minecraft:unbreaking", "minecraft:mending");
		return mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElementIds()
			.map(key -> key.identifier().toString()).sorted().toList();
	}

	/** Display name for an enchantment id, falling back to the raw id. */
	public static String name(String id) {
		Identifier key = Identifier.tryParse(id);
		Minecraft mc = Minecraft.getInstance();
		return key == null || mc.level == null ? id
			: mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOptional(key).map(e -> e.description().getString()).orElse(id);
	}

	/** Level of {@code enchantment} on the stack (0 when absent). */
	public static int level(ItemStack stack, ResourceKey<Enchantment> enchantment) {
		for (Object2IntMap.Entry<Holder<Enchantment>> entry : stack.getEnchantments().entrySet()) {
			if (entry.getKey().is(enchantment)) return entry.getIntValue();
		}
		return 0;
	}
}
