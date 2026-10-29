package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Inv;

/** Keeps your selected hotbar slot fixed: blocks the scroll wheel and/or number keys. */
public final class HotbarLock extends Module {
	public final NumberSetting slot = setting(new NumberSetting("Locked slot", "Slot to lock (0 = whichever was selected when enabled)", 0, 0, 9, 1)
		.format(v -> v < 1 ? "Current" : Integer.toString((int) Math.round(v))));
	public final BooleanSetting blockScroll = setting(new BooleanSetting("Block scroll", "Mouse wheel doesn't change slots", true));
	public final BooleanSetting blockKeys = setting(new BooleanSetting("Block number keys", "1-9 don't change slots", true));

	private int locked;

	public HotbarLock() {
		super("HotbarLock", Category.PLAYER, "Prevents changing the selected hotbar slot.");
	}

	@Override
	protected void onEnable() {
		var p = mc().player;
		locked = slot.intValue() > 0 ? slot.intValue() - 1 : p == null ? 0 : p.getInventory().getSelectedSlot();
	}

	@Override
	public void onTick() {
		var p = mc().player;
		if (p == null) return;
		if (slot.intValue() > 0) locked = slot.intValue() - 1;
		if (blockKeys.get() && p.getInventory().getSelectedSlot() != locked) Inv.swap(locked, false);
	}

	/** Mixin hook: true = swallow the hotbar scroll. */
	public static boolean blocksScroll() {
		return ModuleManager.on(HotbarLock.class) && CoffeeClient.modules().get(HotbarLock.class).blockScroll.get();
	}
}
