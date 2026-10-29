package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ItemListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.InvPacer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Refills hotbar/offhand slots from the rest of your inventory. */
public final class AutoReplenish extends Module {
	public final NumberSetting minCount = setting(new NumberSetting("Min count", "Replenish a slot when it reaches this count", 8, 1, 63, 1));
	public final NumberSetting delay = setting(new NumberSetting("Delay", "Ticks to wait between replenishes", 1, 0, 20, 1));
	public final BooleanSetting offhand = setting(new BooleanSetting("Offhand", "Replenish the offhand as well", true));
	public final BooleanSetting unstackable = setting(new BooleanSetting("Unstackable", "Replenish unstackable items", true));
	public final BooleanSetting sameEnchants = setting(new BooleanSetting("Same enchants", "Only replace unstackables that have the same enchants", true));
	public final BooleanSetting searchHotbar = setting(new BooleanSetting("Search hotbar", "Combine stacks in the hotbar/offhand as a last resort", false));
	public final ItemListSetting excludedItems = setting(new ItemListSetting("Excluded items", "Items that will not be replenished", List.of()));

	/** Slot 0-8 = hotbar, 9 = offhand; the stack each held last tick. */
	private final ItemStack[] lastStacks = new ItemStack[10];
	private boolean prevHadScreen;
	private final InvPacer pacer = new InvPacer();

	public AutoReplenish() {
		super("AutoReplenish", Category.PLAYER, "Automatically refills items in your hotbar or offhand.");
		sameEnchants.visibleWhen(unstackable::get);
		for (Setting<?> s : pacer.settings()) group(s, "Timing");
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public void onEnable() {
		fillStacks();
		pacer.start();
		prevHadScreen = mc().gui.screen() != null;
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null) return;

		boolean hadScreen = mc().gui.screen() != null;
		if (!hadScreen && prevHadScreen) fillStacks();
		prevHadScreen = hadScreen;

		if (hadScreen || mc().player.containerMenu.getItems().size() != 46) return;
		pacer.tick();
		if (!pacer.ready(delay.intValue())) return;

		for (int i = 0; i < 9; i++) checkSlot(i, mc().player.getInventory().getItem(i));
		if (offhand.get()) checkSlot(9, mc().player.getOffhandItem());
	}

	private void checkSlot(int slot, ItemStack stack) {
		int inventorySlot = slot == 9 ? Inv.OFFHAND_INDEX : slot;

		ItemStack previous = lastStacks[slot];
		lastStacks[slot] = stack.copy();

		if (excluded(stack) || excluded(previous)) return;

		int from = -1;

		if (stack.isStackable() && !stack.isEmpty() && stack.getCount() <= minCount.get()) {
			from = findItem(stack, inventorySlot, minCount.get().intValue() - stack.getCount() + 1, true);
		}
		if (previous.isStackable() && stack.isEmpty() && !previous.isEmpty()) {
			from = findItem(previous, inventorySlot, minCount.get().intValue() + 1, false);
		}
		if (unstackable.get() && !previous.isStackable() && stack.isEmpty() && !previous.isEmpty()) {
			from = findItem(previous, inventorySlot, 1, false);
		}

		if (from == -1) return;
		if (from == mc().player.getInventory().getSelectedSlot() || from == Inv.OFFHAND_INDEX) return;
		if (from < 9 && from < inventorySlot
			&& inventorySlot != mc().player.getInventory().getSelectedSlot()
			&& inventorySlot != Inv.OFFHAND_INDEX) return;

		Inv.move(Inv.inventoryId(from), Inv.inventoryId(inventorySlot));
	}

	private int findItem(ItemStack lookFor, int excludedSlot, int goodEnoughCount, boolean mustCombine) {
		int slot = -1;
		int count = 0;
		var inventory = mc().player.getInventory();

		for (int i = inventory.getContainerSize() - 2; i >= (searchHotbar.get() ? 0 : 9); i--) {
			if (i == excludedSlot) continue;

			ItemStack stack = inventory.getItem(i);
			if (stack.getItem() != lookFor.getItem()) continue;
			if (mustCombine && !ItemStack.isSameItemSameComponents(lookFor, stack)) continue;
			if (sameEnchants.get() && !stack.getEnchantments().equals(lookFor.getEnchantments())) continue;

			if (stack.getCount() > count) {
				slot = i;
				count = stack.getCount();
				if (count >= goodEnoughCount) break;
			}
		}
		return slot;
	}

	private boolean excluded(ItemStack stack) {
		return excludedItems.has(stack);
	}

	private void fillStacks() {
		if (mc().player == null) return;
		for (int i = 0; i < 9; i++) lastStacks[i] = mc().player.getInventory().getItem(i).copy();
		lastStacks[9] = mc().player.getOffhandItem().copy();
	}

}
