package dev.coffeeclient.setting;

import dev.coffeeclient.util.ItemIds;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Set;

/** A selection of items, picked from every registered item. */
public class ItemListSetting extends IdListSetting {
	public ItemListSetting(String name, String description, List<String> defaults) {
		super(name, description, defaults, ItemIds::all, false);
		labels(ItemIds::name);
		icons(ItemIds::stack);
	}

	public Set<Item> items() {
		return resolve(ItemIds::of);
	}

	public boolean has(Item item) {
		return items().contains(item);
	}

	public boolean has(ItemStack stack) {
		return !stack.isEmpty() && has(stack.getItem());
	}
}
