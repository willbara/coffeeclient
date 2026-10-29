package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.ModuleCustomElement;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.List;

/** How many of your held item you carry in total (optionally including shulker boxes). */
public final class ItemCounter extends Module {
	public final BooleanSetting includeShulkers = setting(new BooleanSetting("Include shulkers", "Count items inside shulker boxes you carry", true));
	public final BooleanSetting showStacks = setting(new BooleanSetting("Show stacks", "Also show the count in stacks", true));
	public final BooleanSetting showIcon = setting(new BooleanSetting("Icon", "Show the item icon", true));

	private final HudElement element = new Element();

	public ItemCounter() {
		super("ItemCounter", Category.PLAYER, "Counts items of the held type in your inventory.");
	}

	@Override
	public List<HudElement> hudElements() {
		return List.of(element);
	}

	private int count(ItemStack held) {
		var p = mc().player;
		if (p == null || held.isEmpty()) return 0;
		int total = 0;
		for (ItemStack s : p.getInventory()) {
			if (ItemStack.isSameItem(s, held)) total += s.getCount();
			if (includeShulkers.get()) {
				ItemContainerContents contents = s.get(DataComponents.CONTAINER);
				if (contents != null) total += contents.nonEmptyItemCopyStream().filter(i -> ItemStack.isSameItem(i, held)).mapToInt(ItemStack::getCount).sum();
			}
		}
		return total;
	}

	private ItemStack held() {
		var p = mc().player;
		return p == null ? ItemStack.EMPTY : p.getMainHandItem();
	}

	private String label() {
		ItemStack held = held();
		int n = count(held);
		String text = Integer.toString(n);
		if (showStacks.get() && held.getMaxStackSize() > 1 && n >= held.getMaxStackSize()) {
			text += " (" + n / held.getMaxStackSize() + "×" + held.getMaxStackSize() + (n % held.getMaxStackSize() > 0 ? " + " + n % held.getMaxStackSize() : "") + ")";
		}
		return text;
	}

	private final class Element extends ModuleCustomElement {
		Element() {
			super(ItemCounter.this, "item_counter", "Item counter");
		}

		@Override
		public boolean shouldRender() {
			return !held().isEmpty();
		}

		@Override
		public boolean autoStacks() {
			return false;
		}

		@Override
		public int width() {
			return Draw.width(label()) + (showIcon.get() ? 26 : 10);
		}

		@Override
		public int height() {
			return 20;
		}

		@Override
		protected int[] defaultPosition(int w, int h) {
			return new int[] {w / 2 - 91 - width() - 4, h - 22};
		}

		@Override
		public void render(Ui ui, int x, int y, boolean preview) {
			Draw.box(ui.g, x, y, width(), height(), ui.theme.panel, ui.theme.border);
			int tx = x + 5;
			if (showIcon.get() && !held().isEmpty()) {
				ui.g.item(held(), x + 2, y + 2);
				tx = x + 21;
			}
			Draw.text(ui.g, label(), tx, y + 6, ui.theme.text);
		}
	}
}
