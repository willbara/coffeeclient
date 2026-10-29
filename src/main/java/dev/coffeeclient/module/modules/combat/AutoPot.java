package dev.coffeeclient.module.modules.combat;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.CombatTick;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.Rotations;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.List;

/** Throws splash potions from your hotbar at your feet: healing when hurt, and buffs that ran out. */
public final class AutoPot extends Module {
	private static final List<Holder<MobEffect>> BUFFS = List.of(MobEffects.SPEED, MobEffects.STRENGTH, MobEffects.FIRE_RESISTANCE, MobEffects.RESISTANCE);

	public final NumberSetting health = setting(new NumberSetting("Health", "Throw healing when your health is at or below this", 8, 1, 20, 1));
	public final BooleanSetting buffs = setting(new BooleanSetting("Buffs", "Also throw Speed, Strength, Fire Resistance and Resistance when you lack the effect", false));
	public final NumberSetting delay = setting(new NumberSetting("Delay", "Ticks between throws", 10, 1, 40, 1));

	private int cooldown;

	public AutoPot() {
		super("AutoPot", List.of("AutoPotion", "Auto Pot"), Category.COMBAT, "Throws healing and buff splash potions from your hotbar.");
	}

	@Override
	public void onTick() {
		LocalPlayer p = mc().player;
		if (cooldown > 0) cooldown--;
		if (p == null || mc().gameMode == null || cooldown > 0 || mc().gui.screen() != null || p.isDeadOrDying() || CombatTick.busy(CombatTick.Kind.USE, this)) return;
		Inv.FindItemResult pot = Inv.findInHotbar(stack -> wanted(p, stack));
		if (!pot.found()) return;
		if (Rotations.rotate(p.getYRot(), 90, Rotations.NORMAL, () -> Inv.use(pot))) cooldown = delay.intValue();
	}

	private boolean wanted(LocalPlayer p, ItemStack stack) {
		PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
		if (!stack.is(Items.SPLASH_POTION) || contents == null) return false;
		for (MobEffectInstance effect : contents.getAllEffects()) {
			if (effect.getEffect().equals(MobEffects.INSTANT_HEALTH) && p.getHealth() <= health.floatValue()) return true;
			if (buffs.get() && BUFFS.contains(effect.getEffect()) && !p.hasEffect(effect.getEffect())) return true;
		}
		return false;
	}
}
