package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.modules.combat.AutoTotem;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ItemListSetting;
import dev.coffeeclient.util.EnchantmentIds;
import dev.coffeeclient.util.Inv;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.List;

/**
 * Cycles damaged Mending items through the offhand so thrown XP repairs them.
 * Gives way to AutoTotem whenever it wants the offhand.
 */
public final class AutoMend extends Module {
	public final ItemListSetting blacklist = setting(new ItemListSetting("Blacklist", "Items never moved to the offhand", List.of()));
	public final BooleanSetting force = setting(new BooleanSetting("Force", "Replace a non-Mending offhand item too", false));
	public final BooleanSetting autoDisable = setting(new BooleanSetting("Auto disable", "Turn off once everything is repaired", true));

	private boolean didMove;

	public AutoMend() {
		super("AutoMend", List.of("Auto Mend"), Category.PLAYER, "Moves damaged Mending items to your offhand so XP repairs them.");
	}

	@Override
	protected void onEnable() {
		didMove = false;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().gameMode == null || player.containerMenu != player.inventoryMenu) return;
		if (CoffeeClient.modules().get(AutoTotem.class).wantsOffhand()) return;
		if (shouldWait(player.getOffhandItem())) return;

		int slot = nextDamaged(player);
		if (slot >= 0) {
			Inv.move(Inv.inventoryId(slot), Inv.inventoryId(Inv.OFFHAND_INDEX));
			didMove = true;
			return;
		}
		if (!autoDisable.get()) return;
		if (didMove) {
			int empty = Inv.findEmptySlot();
			if (empty >= 0) Inv.move(Inv.inventoryId(Inv.OFFHAND_INDEX), Inv.inventoryId(empty));
		}
		info("Repaired all items, disabling");
		setEnabled(false);
	}

	/** True while the offhand item is still mending, or holds something we may not replace. */
	private boolean shouldWait(ItemStack offhand) {
		if (offhand.isEmpty()) return false;
		if (mending(offhand)) return offhand.getDamageValue() != 0;
		return !force.get();
	}

	private int nextDamaged(LocalPlayer player) {
		for (int i = 0; i < 36; i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (stack.getDamageValue() > 0 && mending(stack) && !blacklist.has(stack)) return i;
		}
		return -1;
	}

	private static boolean mending(ItemStack stack) {
		return EnchantmentIds.level(stack, Enchantments.MENDING) > 0;
	}

	@Override
	public String infoString() {
		LocalPlayer player = mc().player;
		if (player == null) return null;
		int left = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (stack.getDamageValue() > 0 && mending(stack) && !blacklist.has(stack)) left++;
		}
		ItemStack offhand = player.getOffhandItem();
		if (offhand.getDamageValue() > 0 && mending(offhand)) left++;
		return left + " left";
	}
}
