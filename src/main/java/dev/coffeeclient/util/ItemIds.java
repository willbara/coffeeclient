package dev.coffeeclient.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Registry helpers for item ids shared by item-list settings (icons, names, lookups). */
public final class ItemIds {
	private ItemIds() {
	}

	/** Every item id, sorted, without air. */
	public static List<String> all() {
		return BuiltInRegistries.ITEM.keySet().stream()
			.map(Identifier::toString)
			.filter(id -> !id.equals("minecraft:air"))
			.sorted()
			.toList();
	}

	public static Item of(String id) {
		Identifier key = Identifier.tryParse(id);
		return key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
	}

	public static String id(Item item) {
		Identifier key = BuiltInRegistries.ITEM.getKey(item);
		return key == null ? "" : key.toString();
	}

	/** An item stack for the id (empty when unknown), used as a picker icon. */
	public static ItemStack stack(String id) {
		Item item = of(id);
		return item == null ? ItemStack.EMPTY : new ItemStack(item);
	}

	/** Display name for an item id, falling back to the raw id. */
	public static String name(String id) {
		ItemStack stack = stack(id);
		return stack.isEmpty() ? id : stack.getHoverName().getString();
	}
}
