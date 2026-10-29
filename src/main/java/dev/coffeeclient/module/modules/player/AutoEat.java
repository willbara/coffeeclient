package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.ItemListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.CombatTick;
import dev.coffeeclient.util.Inv;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** Eats a hotbar food when you get hungry, and a golden apple when you are hurt. */
public final class AutoEat extends Module {
	public enum Pick {
		/** The food with the most saturation. */
		Best,
		/** The smallest food that fills the gap, so a big meal is not wasted on a small one. */
		Efficient
	}

	public final NumberSetting hunger = setting(new NumberSetting("Hunger", "Eat when your food bar is at or below this (20 is full)", 14, 1, 19, 1));
	public final NumberSetting injured = setting(new NumberSetting("Injured health", "Also eat a golden apple when your health is at or below this, even when full (0 is off)", 0, 0, 20, 1));
	public final EnumSetting<Pick> pick = setting(new EnumSetting<>("Pick", "Which food to eat when several are in the hotbar", Pick.Best));
	public final ItemListSetting avoid = setting(new ItemListSetting("Avoid", "Foods never eaten for hunger",
		List.of("minecraft:golden_apple", "minecraft:enchanted_golden_apple", "minecraft:chorus_fruit", "minecraft:rotten_flesh",
			"minecraft:spider_eye", "minecraft:poisonous_potato", "minecraft:pufferfish", "minecraft:suspicious_stew")));

	public final BooleanSetting chorus = group(new BooleanSetting("Chorus escape", "Eat a chorus fruit to teleport away when your health is low", false), "Chorus fruit");
	public final NumberSetting chorusHealth = group(new NumberSetting("Chorus health", "Health at or below which the chorus fruit is eaten", 6, 1, 20, 1), "Chorus fruit");

	private boolean eating;

	public AutoEat() {
		super("AutoEat", Category.PLAYER, "Eats food from your hotbar when you are hungry or hurt.");
		chorusHealth.visibleWhen(chorus::get);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public dev.coffeeclient.setting.IdListSetting embeddedList() {
		return avoid;
	}

	@Override
	protected void onDisable() {
		stopEating();
	}

	@Override
	public void onTick() {
		LocalPlayer p = mc().player;
		if (p == null || mc().gameMode == null) return;
		boolean escape = chorus.get() && p.getHealth() <= chorusHealth.intValue() && hasChorus(p);
		boolean emergency = escape || injured.intValue() > 0 && p.getHealth() <= injured.intValue();
		int slot = food(p, emergency, escape);
		if (eating) {
			// Stop when the meal is done, the food is gone, or nothing needs eating any more.
			boolean needed = slot >= 0 && (emergency || p.getFoodData().getFoodLevel() <= hunger.intValue());
			if (!needed || mc().gui.screen() != null) stopEating();
			else {
				mc().options.keyUse.setDown(true);
				CombatTick.claim(CombatTick.Kind.USE, this, 0, 1);
			}
			return;
		}
		if (slot < 0 || p.isUsingItem() || mc().gui.screen() != null) return;
		if (!emergency && p.getFoodData().getFoodLevel() > hunger.intValue()) return;
		Inv.swap(slot, true);
		mc().options.keyUse.setDown(true);
		eating = true;
		CombatTick.claim(CombatTick.Kind.USE, this, 0, 1);
	}

	private void stopEating() {
		if (!eating) return;
		eating = false;
		CombatTick.release(this);
		mc().options.keyUse.setDown(false);
		Inv.swapBack();
	}

	/** The hotbar slot to eat from, or -1. While eating, the slot already held counts as the choice. */
	private int food(LocalPlayer p, boolean emergency, boolean escape) {
		int need = 20 - p.getFoodData().getFoodLevel();
		int best = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = p.getInventory().getItem(i);
			FoodProperties food = stack.get(DataComponents.FOOD);
			if (food == null) continue;
			if (!wanted(stack.getItem(), escape, emergency, avoid.has(stack))) continue;
			if (!emergency && need == 0 && !food.canAlwaysEat()) continue;
			if (best < 0 || better(pick.get(), food, p.getInventory().getItem(best).get(DataComponents.FOOD), need)) best = i;
		}
		return best;
	}

	private static boolean hasChorus(LocalPlayer p) {
		for (int i = 0; i < 9; i++) if (p.getInventory().getItem(i).is(Items.CHORUS_FRUIT)) return true;
		return false;
	}

	/** Chorus fruit while escaping, a golden apple while hurt, otherwise any food not on the Avoid list. */
	static boolean wanted(Item item, boolean escape, boolean emergency, boolean avoided) {
		if (escape) return item == Items.CHORUS_FRUIT;
		if (emergency) return item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE;
		return !avoided;
	}

	static boolean better(Pick pick, FoodProperties a, FoodProperties b, int need) {
		if (pick == Pick.Best) return a.saturation() > b.saturation();
		boolean aFills = a.nutrition() >= need, bFills = b.nutrition() >= need;
		if (aFills != bFills) return aFills;
		return aFills ? a.nutrition() < b.nutrition() : a.nutrition() > b.nutrition();
	}
}
