package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Sounds;
import net.minecraft.world.item.ItemStack;

/** Warns when the tool in your hand is about to break. */
public final class DurabilityAlert extends Module {
	public enum Mode { USES_LEFT, PERCENT }

	public final EnumSetting<Mode> mode = setting(new EnumSetting<>("Threshold type", "Uses left or percent", Mode.USES_LEFT));
	public final NumberSetting threshold = setting(new NumberSetting("Threshold", "Warn at or below this", 10, 1, 100, 1));
	public final BooleanSetting offhand = setting(new BooleanSetting("Check off hand", "Also watch the off-hand item", true));
	public final BooleanSetting sound = setting(new BooleanSetting("Sound", "Play a warning sound", true));
	public final BooleanSetting everyUse = setting(new BooleanSetting("Warn every use", "Repeat as durability keeps dropping", false));

	private int lastWarnedMain = Integer.MAX_VALUE, lastWarnedOff = Integer.MAX_VALUE;

	public DurabilityAlert() {
		super("DurabilityAlert", Category.PLAYER, "Warns when a held tool is about to break.");
	}

	@Override
	public void onTick() {
		var p = mc().player;
		if (p == null) return;
		lastWarnedMain = check(p.getMainHandItem(), lastWarnedMain);
		if (offhand.get()) lastWarnedOff = check(p.getOffhandItem(), lastWarnedOff);
	}

	/** @return the new "last warned at" value */
	private int check(ItemStack stack, int lastWarned) {
		if (stack.isEmpty() || !stack.isDamageableItem()) return Integer.MAX_VALUE;
		int left = stack.getMaxDamage() - stack.getDamageValue();
		double value = mode.get() == Mode.USES_LEFT ? left : left * 100.0 / stack.getMaxDamage();
		if (value > threshold.get()) return Integer.MAX_VALUE;
		boolean warn = lastWarned == Integer.MAX_VALUE || (everyUse.get() && left < lastWarned);
		if (!warn) return lastWarned;
		notify(stack.getHoverName().getString() + ": " + left + " uses left");
		if (sound.get()) Sounds.play("minecraft:entity.item.break", 1.6f, 0.4f);
		return left;
	}
}
