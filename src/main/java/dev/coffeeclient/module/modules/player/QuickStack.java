package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.InvPacer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Adds "Stack" (move items the container already has) and "Deposit" buttons to container screens.
 * The moves go out one per click delay (all at once at 0), paced like the other inventory modules.
 */
public final class QuickStack extends Module {
	public enum Corner { TOP_RIGHT, TOP_LEFT, BOTTOM_RIGHT }

	public final BooleanSetting includeHotbar = setting(new BooleanSetting("Include hotbar", "Also move items from the hotbar", false));
	public final BooleanSetting depositButton = setting(new BooleanSetting("Deposit button", "Add a button that moves everything", true));
	public final EnumSetting<Corner> corner = setting(new EnumSetting<>("Button position", "Screen corner for the buttons", Corner.TOP_RIGHT));
	public final NumberSetting clickDelay = group(new NumberSetting("Click delay", "Ticks between moves (0 = all at once)", 0, 0, 10, 1), "Timing");

	private final InvPacer pacer = new InvPacer();
	private final ArrayDeque<Integer> queue = new ArrayDeque<>();
	private AbstractContainerMenu queueMenu;
	private int moved;

	public QuickStack() {
		super("QuickStack", Category.PLAYER, "Adds stack-to-container and deposit buttons.");
		for (Setting<?> s : pacer.settings()) group(s, "Timing");
	}

	@Override
	public void onTick() {
		var mc = mc();
		if (queue.isEmpty()) return;
		if (mc.player == null || mc.gameMode == null || mc.player.containerMenu != queueMenu) {
			queue.clear();
			return;
		}
		pacer.tick();
		while (!queue.isEmpty() && pacer.ready(clickDelay.intValue())) {
			mc.gameMode.handleContainerInput(queueMenu.containerId, queue.poll(), 0, ContainerInput.QUICK_MOVE, mc.player);
			moved++;
		}
		if (queue.isEmpty()) notify("Moved " + moved + " stacks");
	}

	@Override
	protected void init(EventBus bus) {
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (!isEnabled() || !(screen instanceof AbstractContainerScreen<?> acs)) return;
			if (screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen) return;
			if (containerSlots(acs.getMenu()).isEmpty()) return;
			int bw = 50, gap = 2;
			int count = depositButton.get() ? 2 : 1;
			int x0 = switch (corner.get()) {
				case TOP_LEFT -> 4;
				default -> w - 4 - count * bw - (count - 1) * gap;
			};
			int y0 = corner.get() == Corner.BOTTOM_RIGHT ? h - 24 : 4;
			List<net.minecraft.client.gui.components.AbstractWidget> widgets = Screens.getWidgets(screen);
			widgets.add(Button.builder(Component.literal("Stack"), b -> move(acs.getMenu(), true)).bounds(x0, y0, bw, 20).build());
			if (depositButton.get()) {
				widgets.add(Button.builder(Component.literal("Deposit"), b -> move(acs.getMenu(), false)).bounds(x0 + bw + gap, y0, bw, 20).build());
			}
		});
	}

	private static List<Slot> containerSlots(AbstractContainerMenu menu) {
		return menu.slots.stream().filter(s -> !(s.container instanceof Inventory)).toList();
	}

	private void move(AbstractContainerMenu menu, boolean onlyMatching) {
		var mc = mc();
		if (mc.player == null || mc.gameMode == null) return;
		List<Slot> container = containerSlots(menu);
		queue.clear();
		moved = 0;
		for (Slot slot : menu.slots) {
			if (!(slot.container instanceof Inventory) || !slot.hasItem()) continue;
			int invIndex = slot.getContainerSlot();
			if (invIndex >= 36) continue; // armor / offhand
			if (invIndex < 9 && !includeHotbar.get()) continue;
			ItemStack stack = slot.getItem();
			if (onlyMatching && container.stream().noneMatch(c -> ItemStack.isSameItemSameComponents(c.getItem(), stack))) continue;
			queue.add(slot.index);
		}
		if (queue.isEmpty()) {
			notify("Nothing to move");
			return;
		}
		queueMenu = menu;
		pacer.start();
	}
}
