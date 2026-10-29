package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Damage;
import dev.coffeeclient.util.EnchantmentIds;
import dev.coffeeclient.util.Inv;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.List;

/**
 * Switches to the best weapon in your hotbar when you attack (by hand or with KillAura): an axe
 * against a raised shield, a mace while you fall far enough to smash, else the best sword or axe
 * (spears and tridents too, when allowed), and optionally back to your slot after the hit.
 */
public final class AutoWeapon extends Module {
	public enum Weapon { SWORD, AXE }

	/** A mace smash needs a fall longer than this. */
	private static final double SMASH_FALL = 1.5;

	public final EnumSetting<Weapon> weapon = setting(new EnumSetting<>("Weapon", "Preferred weapon type", Weapon.SWORD));
	public final NumberSetting threshold = setting(new NumberSetting("Threshold", "Use another weapon when it deals this much more damage", 4, 0, 20, 0.5));
	public final BooleanSetting shieldBreaker = setting(new BooleanSetting("Shield breaker", "Take an axe when the target holds up a shield", true));
	public final BooleanSetting mace = setting(new BooleanSetting("Mace", "Take a mace while you fall far enough to smash (Density and Breach counted)", true));
	public final BooleanSetting spears = setting(new BooleanSetting("Spears", "Consider spears", false));
	public final BooleanSetting tridents = setting(new BooleanSetting("Tridents", "Consider tridents", false));
	public final BooleanSetting swapBack = setting(new BooleanSetting("Swap back", "Go back to your slot the tick after the hit", false));
	public final BooleanSetting antiBreak = setting(new BooleanSetting("Anti break", "Skip weapons with 10 or fewer uses left", false));

	private boolean back;

	public AutoWeapon() {
		super("AutoWeapon", List.of("Auto Weapon", "AutoSword", "AttributeSwap"), Category.COMBAT, "Switches to your best weapon when you attack.");
		// Before the attack packet, so the hit uses the new weapon.
		listen(Events.AttackEntity.class, e -> {
			if (!(e.target() instanceof LivingEntity target)) return;
			int slot = bestWeapon(target);
			if (slot == mc().player.getInventory().getSelectedSlot()) return;
			Inv.swap(slot, swapBack.get());
			back = swapBack.get();
		});
	}

	@Override
	public void onTick() {
		if (!back) return;
		back = false;
		Inv.swapBack();
	}

	private int bestWeapon(LivingEntity target) {
		LocalPlayer player = mc().player;
		if (shieldBreaker.get() && target instanceof Player p && p.isBlocking()) {
			int axe = best(target, s -> s.is(ItemTags.AXES));
			if (axe >= 0) return axe;
		}
		if (mace.get() && player.fallDistance > SMASH_FALL) {
			int found = best(target, s -> s.getItem() instanceof MaceItem);
			if (found >= 0) return found;
		}
		int sword = best(target, s -> s.is(ItemTags.SWORDS)), axe = best(target, s -> s.is(ItemTags.AXES));
		int preferred = weapon.get() == Weapon.SWORD ? sword : axe, other = weapon.get() == Weapon.SWORD ? axe : sword;
		int pick = preferred >= 0 ? preferred : other;
		if (preferred >= 0 && other >= 0 && damage(target, other) - damage(target, preferred) > threshold.get()) pick = other;
		for (int extra : new int[]{spears.get() ? best(target, s -> s.is(ItemTags.SPEARS)) : -1, tridents.get() ? best(target, s -> s.getItem() instanceof TridentItem) : -1}) {
			if (extra >= 0 && (pick < 0 || damage(target, extra) - damage(target, pick) > threshold.get())) pick = extra;
		}
		return pick >= 0 ? pick : player.getInventory().getSelectedSlot();
	}

	/** The hotbar slot of the {@code kind} weapon that deals the most, or -1. */
	private int best(LivingEntity target, java.util.function.Predicate<ItemStack> kind) {
		int slot = -1;
		double most = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = mc().player.getInventory().getItem(i);
			if (!kind.test(stack) || antiBreak.get() && stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= 10) continue;
			double d = damage(target, i);
			if (d > most) {
				most = d;
				slot = i;
			}
		}
		return slot;
	}

	private double damage(LivingEntity target, int slot) {
		LocalPlayer player = mc().player;
		ItemStack stack = player.getInventory().getItem(slot);
		double damage = Damage.attack(player, target, stack);
		if (!(stack.getItem() instanceof MaceItem) || player.fallDistance <= SMASH_FALL) return damage;
		double fall = player.fallDistance;
		// The smash bonus (MaceItem#getAttackDamageBonus), Density's per-block bonus, and armour Breach ignores.
		double smash = fall <= 3 ? 4 * fall : fall <= 8 ? 12 + 2 * (fall - 3) : 22 + fall - 8;
		double density = 0.5 * EnchantmentIds.level(stack, Enchantments.DENSITY) * fall;
		// shortcut: Breach is scored as a flat share of the target's armour, not run through the armour formula; fine for ranking maces.
		double breach = 0.15 * EnchantmentIds.level(stack, Enchantments.BREACH) * target.getAttributeValue(Attributes.ARMOR);
		return damage + smash + density + breach;
	}

	@Override
	public String infoString() {
		return weapon.display();
	}
}
