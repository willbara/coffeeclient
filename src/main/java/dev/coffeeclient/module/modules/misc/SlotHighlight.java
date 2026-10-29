package dev.coffeeclient.module.modules.misc;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.render.BlockESP;
import dev.coffeeclient.module.modules.world.ChestSearch;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.List;

/**
 * Marks container slots worth a second look in a stash: enchantments above their maximum, rockets
 * with more explosions than crafting allows, renamed items, written books, and shulker boxes with
 * a shulker box inside. A search bar above container screens dims every slot that doesn't match
 * (by id or name, looking inside shulkers too), and can feed ChestSearch's search.
 */
public final class SlotHighlight extends Module {
	public final BooleanSetting illegal = group(new BooleanSetting("Illegal enchants", "Enchantments above their maximum level", true), "Highlight");
	public final BooleanSetting lagRockets = group(new BooleanSetting("Lag rockets", "Rockets with more than 7 explosions", true), "Highlight");
	public final BooleanSetting renamed = group(new BooleanSetting("Renamed", "Items with a custom name", false), "Highlight");
	public final BooleanSetting written = group(new BooleanSetting("Written books", "Signed books", false), "Highlight");
	public final BooleanSetting nested = group(new BooleanSetting("Nested shulkers", "Shulker boxes holding a shulker box", true), "Highlight");
	public final ColorSetting color = group(new ColorSetting("Color", "Slot highlight colour", 0x80FF55FF), "Highlight");
	public final BooleanSetting searchBar = group(new BooleanSetting("Search bar", "A search field above container screens", true), "Search");
	public final NumberSetting dim = group(new NumberSetting("Dim", "How dark non-matching slots get", 170, 40, 255, 5), "Search");
	public final BooleanSetting syncChestSearch = group(new BooleanSetting("Feed ChestSearch", "Typing also sets ChestSearch's search", false), "Search");

	/** The bar on the open screen, and what it holds (kept across screens). */
	private static EditBox box;
	private static String query = "";

	public SlotHighlight() {
		super("SlotHighlight", List.of("ItemSearchBar", "LoreLocator"), Category.MISC, "Marks interesting items in containers and adds a search bar.");
		for (Setting<?> s : List.of(dim, syncChestSearch)) s.visibleWhen(searchBar::get);
	}

	@Override
	protected void init(EventBus bus) {
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			box = null;
			if (!isEnabled() || !searchBar.get() || !(screen instanceof AbstractContainerScreen<?>) || screen instanceof CreativeModeInventoryScreen) return;
			EditBox field = new EditBox(mc.font, w / 2 - 60, 2, 120, 14, Component.literal("Search"));
			field.setHint(Component.literal("Search items"));
			field.setValue(query);
			field.setResponder(text -> {
				query = text;
				if (syncChestSearch.get()) CoffeeClient.modules().get(ChestSearch.class).search.set(text);
			});
			Screens.getWidgets(screen).add(field);
			box = field;
		});
	}

	@Override
	protected void onDisable() {
		box = null;
	}

	/** AbstractContainerScreenMixin: true while the bar takes the key (so E or a hotbar number doesn't act). */
	public static boolean typing() {
		return box != null && box.isFocused() && box.canConsumeInput();
	}

	public static EditBox box() {
		return box;
	}

	/** AbstractContainerScreenMixin, before the slot's item: the highlight behind it. */
	public static void behind(GuiGraphicsExtractor g, Slot slot) {
		SlotHighlight m = ModuleManager.ifOn(SlotHighlight.class);
		if (m == null || !slot.hasItem() || !m.interesting(slot.getItem())) return;
		g.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, m.color.argb());
	}

	/** AbstractContainerScreenMixin, after the slot's item: dim it when the search doesn't match. */
	public static void after(GuiGraphicsExtractor g, Slot slot) {
		SlotHighlight m = ModuleManager.ifOn(SlotHighlight.class);
		if (m == null || box == null || query.isBlank() || matches(slot.getItem(), query)) return;
		g.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, m.dim.intValue() << 24);
	}

	private static boolean matches(ItemStack stack, String q) {
		if (stack.isEmpty()) return false;
		if (BlockESP.matchesItem(stack, q)) return true;
		ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
		return contents != null && contents.nonEmptyItemCopyStream().anyMatch(s -> BlockESP.matchesItem(s, q));
	}

	private boolean interesting(ItemStack stack) {
		if (renamed.get() && stack.has(DataComponents.CUSTOM_NAME)) return true;
		if (written.get() && stack.is(Items.WRITTEN_BOOK)) return true;
		if (lagRockets.get()) {
			Fireworks f = stack.get(DataComponents.FIREWORKS);
			if (f != null && f.explosions().size() > 7) return true;
		}
		if (nested.get()) {
			ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
			if (contents != null && contents.nonEmptyItemCopyStream().anyMatch(s -> s.has(DataComponents.CONTAINER))) return true;
		}
		if (illegal.get()) {
			ItemEnchantments enchants = EnchantmentHelper.getEnchantmentsForCrafting(stack);
			for (Holder<Enchantment> e : enchants.keySet()) if (enchants.getLevel(e) > e.value().getMaxLevel()) return true;
		}
		return false;
	}
}
