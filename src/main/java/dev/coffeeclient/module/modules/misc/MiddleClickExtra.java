package dev.coffeeclient.module.modules.misc;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.Labeled;
import dev.coffeeclient.util.Inv;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Middle-click uses an item without holding it: throws a pearl, fires a rocket or casts a rod, by
 * swapping to it for the one use and straight back. Looking at a player leaves the click to Friends.
 */
public final class MiddleClickExtra extends Module {
	public enum Action implements Labeled {
		PEARL("Pearl", Items.ENDER_PEARL), FIREWORK("Firework", Items.FIREWORK_ROCKET), ROD("Fishing rod", Items.FISHING_ROD),
		XP("XP bottle", Items.EXPERIENCE_BOTTLE), WIND_CHARGE("Wind charge", Items.WIND_CHARGE);

		private final String label;
		final Item item;

		Action(String label, Item item) {
			this.label = label;
			this.item = item;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public final EnumSetting<Action> action = setting(new EnumSetting<>("Action", "Item used on middle click", Action.PEARL));
	public final BooleanSetting inventory = setting(new BooleanSetting("From inventory", "Also use the item from outside the hotbar", true));
	public final BooleanSetting onlyGliding = setting(new BooleanSetting("Only while gliding", "Fireworks only fire while you fly an elytra", true));

	public MiddleClickExtra() {
		super("MiddleClickExtra", List.of("MCP", "MiddleClickPearl"), Category.MISC, "Middle-click throws a pearl, fires a rocket and more.");
		onlyGliding.visibleWhen(() -> action.get() == Action.FIREWORK);
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.MouseClick.class, e -> {
			if (e.isCancelled() || e.button != GLFW.GLFW_MOUSE_BUTTON_MIDDLE || e.action != GLFW.GLFW_PRESS) return;
			if (use()) e.cancel();
		});
	}

	private boolean use() {
		LocalPlayer player = mc().player;
		if (player == null || mc().gameMode == null || mc().crosshairPickEntity instanceof Player) return false;
		Item item = action.get().item;
		if (item == Items.FIREWORK_ROCKET && onlyGliding.get() && !player.isFallFlying()) return false;
		Inv.FindItemResult hotbar = Inv.findInHotbar(s -> s.is(item));
		if (hotbar.found()) {
			Inv.use(hotbar);
			return true;
		}
		int slot = inventory.get() ? Inv.find(s -> s.is(item), 9, 35).slot() : -1;
		return slot >= 0 && Inv.withItem(slot, true, () -> mc().gameMode.useItem(player, InteractionHand.MAIN_HAND));
	}

	@Override
	public String infoString() {
		return action.display();
	}
}
