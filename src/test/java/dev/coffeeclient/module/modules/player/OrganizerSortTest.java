package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.module.modules.player.Organizer.SortMode;
import dev.coffeeclient.util.ItemIds;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrganizerSortTest {
	@Test
	void sortModesOrderStacks() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		// Unit tests have no data pack, so bind the item holders to empty components before making stacks.
		BuiltInRegistries.ITEM.listElements().filter(h -> !h.areComponentsBound())
			.forEach(h -> h.bindComponents(DataComponentMap.EMPTY));
		List<ItemStack> stacks = new ArrayList<>(List.of(
			new ItemStack(Items.STONE, 3),
			new ItemStack(Items.DIRT, 64),
			new ItemStack(Items.APPLE, 32),
			new ItemStack(Items.STONE, 10)
		));

		stacks.sort(Organizer.sortOrder(SortMode.ID));
		assertEquals(List.of("minecraft:apple x32", "minecraft:dirt x64", "minecraft:stone x10",
			"minecraft:stone x3"), describe(stacks));

		stacks.sort(Organizer.sortOrder(SortMode.TYPE));
		assertEquals(List.of("minecraft:dirt x64", "minecraft:stone x10", "minecraft:stone x3",
			"minecraft:apple x32"), describe(stacks));

		stacks.sort(Organizer.sortOrder(SortMode.COUNT));
		assertEquals(List.of("minecraft:dirt x64", "minecraft:apple x32", "minecraft:stone x10",
			"minecraft:stone x3"), describe(stacks));
	}

	private static List<String> describe(List<ItemStack> stacks) {
		return stacks.stream().map(s -> ItemIds.id(s.getItem()) + " x" + s.getCount()).toList();
	}
}
