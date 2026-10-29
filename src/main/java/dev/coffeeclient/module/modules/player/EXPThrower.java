package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.EnchantmentIds;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.util.Player;
import dev.coffeeclient.util.Rotations;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.List;
import java.util.function.Predicate;

/**
 * Automatically throws XP bottles from your hotbar or offhand (or the main inventory, swapped in
 * and back in one flush). The server sees you look straight down for the throw while your camera
 * stays where it is. Mending only starts once a worn or held Mending item drops below Start and
 * keeps going until all of them reach Stop, so no bottles are wasted on full gear.
 */
public final class EXPThrower extends Module {
	private static final Predicate<ItemStack> BOTTLE = stack -> stack.is(Items.EXPERIENCE_BOTTLE);

	public final BooleanSetting onHold = setting(new BooleanSetting("On hold",
		"Only throw while you hold right-click with XP bottles in your main hand", false));
	public final NumberSetting perThrow = setting(new NumberSetting("Bottles per throw", "Bottles thrown at once", 1, 1, 5, 1));
	public final NumberSetting delay = setting(new NumberSetting("Delay", "Ticks between throws", 0, 0, 20, 1));
	public final BooleanSetting inventory = setting(new BooleanSetting("From inventory", "Also use bottles outside the hotbar", false));
	public final BooleanSetting pauseMoving = setting(new BooleanSetting("Pause while moving", "Don't throw while you walk", false));
	public final BooleanSetting mendingOnly = setting(new BooleanSetting("Mending only", "Only throw while worn or held Mending gear needs repair", false));
	public final NumberSetting start = setting(new NumberSetting("Start", "Start throwing below this durability", 80, 1, 100, 1).format(v -> (int) v + "%"));
	public final NumberSetting stop = setting(new NumberSetting("Stop", "Stop once all Mending gear is at this durability", 100, 1, 100, 1).format(v -> (int) v + "%"));

	private int timer;
	private boolean mending;

	public EXPThrower() {
		super("EXPThrower", Category.PLAYER, "Automatically throws XP bottles from your hotbar.");
		for (Setting<?> s : List.of(start, stop)) s.visibleWhen(mendingOnly::get);
	}

	@Override
	protected void onEnable() {
		timer = 0;
		mending = false;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().gameMode == null) return;
		if (onHold.get() && (!mc().options.keyUse.isDown() || !player.getMainHandItem().is(Items.EXPERIENCE_BOTTLE))) return;
		if (pauseMoving.get() && Player.isMoving()) return;
		if (mendingOnly.get() && !needsMending(player)) return;
		if (timer-- > 0) return;
		Inv.FindItemResult hotbar = Inv.findInHotbar(BOTTLE);
		int stash = hotbar.found() || !inventory.get() ? -1 : Inv.find(BOTTLE, 9, 35).slot();
		if (!hotbar.found() && stash < 0) return;
		timer = delay.intValue();
		int n = perThrow.intValue();
		Rotations.rotate(player.getYRot(), 90, Rotations.NORMAL, () -> {
			if (hotbar.found()) {
				for (int i = 0; i < n; i++) Inv.use(hotbar);
			} else {
				Inv.withItem(stash, true, () -> {
					for (int i = 0; i < n; i++) mc().gameMode.useItem(player, InteractionHand.MAIN_HAND);
				});
			}
		});
	}

	/** Hysteresis between Start and Stop over the worn and held Mending items. */
	private boolean needsMending(LocalPlayer player) {
		double worst = 1;
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			ItemStack stack = player.getItemBySlot(slot);
			if (stack.isDamageableItem() && EnchantmentIds.level(stack, Enchantments.MENDING) > 0)
				worst = Math.min(worst, 1 - stack.getDamageValue() / (double) stack.getMaxDamage());
		}
		if (worst * 100 < start.get()) mending = true;
		else if (worst * 100 >= stop.get()) mending = false;
		return mending;
	}
}
