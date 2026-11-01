package dev.coffeeclient.module.modules.render;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemESPGroupTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		// Unit tests have no data pack, so bind the item holders to empty components before making stacks.
		BuiltInRegistries.ITEM.listElements().filter(h -> !h.areComponentsBound())
			.forEach(h -> h.bindComponents(DataComponentMap.EMPTY));
	}

	@Test
	void similarGroupsSameItemAndName() {
		ItemESP esp = new ItemESP();
		ItemStack fresh = new ItemStack(Items.DIAMOND_SWORD);
		ItemStack worn = new ItemStack(Items.DIAMOND_SWORD);
		worn.setDamageValue(10);
		assertTrue(esp.sameGroup(fresh, worn));

		ItemStack named = new ItemStack(Items.DIAMOND_SWORD);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Lucky"));
		assertFalse(esp.sameGroup(fresh, named));
		assertFalse(esp.sameGroup(fresh, new ItemStack(Items.DIAMOND_PICKAXE)));
	}

	@Test
	void exactOnlyWhenSimilarGroupingIsOff() {
		ItemESP esp = new ItemESP();
		esp.groupSimilar.set(false);
		ItemStack fresh = new ItemStack(Items.DIAMOND_SWORD);
		ItemStack worn = new ItemStack(Items.DIAMOND_SWORD);
		worn.setDamageValue(10);
		assertFalse(esp.sameGroup(fresh, worn));
		assertTrue(esp.sameGroup(fresh, new ItemStack(Items.DIAMOND_SWORD)));
	}
}
