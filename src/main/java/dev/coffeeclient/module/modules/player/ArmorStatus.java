package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.ModuleCustomElement;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.modules.misc.InvLeak;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.util.Colors;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Your armor (and optionally held items) with durability, as a HUD strip. */
public final class ArmorStatus extends Module {
	public enum Layout { HORIZONTAL, VERTICAL }
	public enum Display { PERCENT, REMAINING, BAR, BOSS_BAR }

	public final EnumSetting<Layout> layout = setting(new EnumSetting<>("Layout", "Row or column", Layout.HORIZONTAL));
	public final EnumSetting<Display> display = setting(new EnumSetting<>("Durability as", "Percent, uses left, a small bar, or boss-bar style rows (one wide bar per piece)", Display.PERCENT));
	public final BooleanSetting hands = setting(new BooleanSetting("Held items", "Include main hand and off hand", false));
	public final BooleanSetting colorize = setting(new BooleanSetting("Colorize", "Green to red by durability", true));
	public final BooleanSetting background = setting(new BooleanSetting("Background", "Panel behind the items", true));

	private final HudElement element = new Element();

	public ArmorStatus() {
		super("ArmorStatus", Category.PLAYER, "Shows armor durability on screen.");
		layout.visibleWhen(() -> display.get() != Display.BOSS_BAR);
	}

	@Override
	public List<HudElement> hudElements() {
		return List.of(element);
	}

	private List<ItemStack> items() {
		List<ItemStack> out = new ArrayList<>();
		var p = mc().player;
		if (p == null) return out;
		// InvLeak indices: 39 head down to 36 feet, the selected hotbar slot, 40 offhand.
		int index = 39;
		for (EquipmentSlot s : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) out.add(InvLeak.hud(p.getItemBySlot(s), index--));
		if (hands.get()) {
			out.add(InvLeak.hud(p.getMainHandItem(), p.getInventory().getSelectedSlot()));
			out.add(InvLeak.hud(p.getOffhandItem(), 40));
		}
		return out;
	}

	private final class Element extends ModuleCustomElement {
		private static final int CELL_W = 40;
		private static final int BOSS_W = 130;
		private static final int BOSS_ROW = 22;

		Element() {
			super(ArmorStatus.this, "armor_status", "Armor status");
		}

		private int count() {
			return hands.get() ? 6 : 4;
		}

		@Override
		public boolean autoStacks() {
			return false;
		}

		@Override
		public boolean shouldRender() {
			return items().stream().anyMatch(i -> !i.isEmpty());
		}

		private boolean bossBar() {
			return display.get() == Display.BOSS_BAR;
		}

		private boolean horizontal() {
			return layout.get() == Layout.HORIZONTAL && !bossBar();
		}

		@Override
		public int width() {
			return bossBar() ? BOSS_W : horizontal() ? count() * CELL_W : CELL_W + 4;
		}

		@Override
		public int height() {
			return bossBar() ? count() * BOSS_ROW + 4 : horizontal() ? 28 : count() * 20 + 4;
		}

		@Override
		protected int[] defaultPosition(int w, int h) {
			return new int[] {w / 2 + 96, h - 30};
		}

		@Override
		public void render(Ui ui, int x, int y, boolean preview) {
			if (background.get()) Draw.box(ui.g, x, y, width(), height(), ui.theme.panel, ui.theme.border);
			List<ItemStack> list = items();
			for (int i = 0; i < list.size(); i++) {
				ItemStack stack = list.get(i);
				if (bossBar()) {
					renderBossRow(ui, stack, x + 2, y + 2 + i * BOSS_ROW);
					continue;
				}
				int cx = horizontal() ? x + i * CELL_W + 2 : x + 2;
				int cy = horizontal() ? y + 2 : y + 2 + i * 20;
				if (stack.isEmpty()) continue;
				ui.g.item(stack, cx, cy);
				if (!stack.isDamageableItem()) {
					if (stack.getCount() > 1) Draw.text(ui.g, Integer.toString(stack.getCount()), cx + 18, cy + 4, ui.theme.text);
					continue;
				}
				int max = stack.getMaxDamage(), left = max - stack.getDamageValue();
				float f = left / (float) max;
				int c = colorize.get() ? Colors.lerp(0xFFE0584F, 0xFF5CD65C, f) : ui.theme.text;
				switch (display.get()) {
					case PERCENT -> Draw.text(ui.g, Math.round(f * 100) + "%", cx + 18, cy + 4, c);
					case REMAINING -> Draw.text(ui.g, Integer.toString(left), cx + 18, cy + 4, c);
					case BOSS_BAR -> {
					}
					case BAR -> {
						Draw.rect(ui.g, cx + 18, cy + 7, 18, 3, ui.theme.control);
						Draw.rect(ui.g, cx + 18, cy + 7, Math.round(18 * f), 3, c);
					}
				}
			}
		}

		/** One boss-bar row: the item, its name, and a wide bar that drains with durability. */
		private void renderBossRow(Ui ui, ItemStack stack, int x, int y) {
			if (stack.isEmpty()) return;
			ui.g.item(stack, x, y);
			int bx = x + 20, bw = BOSS_W - 26;
			boolean damageable = stack.isDamageableItem();
			int max = damageable ? stack.getMaxDamage() : 1, left = damageable ? max - stack.getDamageValue() : 1;
			float f = left / (float) max;
			int c = colorize.get() ? Colors.lerp(0xFFE0584F, 0xFF5CD65C, f) : ui.theme.text;
			String name = stack.getHoverName().getString();
			if (name.length() > 16) name = name.substring(0, 15) + "…";
			Draw.text(ui.g, name, bx, y - 1, ui.theme.text);
			if (damageable) Draw.text(ui.g, Math.round(f * 100) + "%", bx + bw - 24, y - 1, c);
			Draw.rect(ui.g, bx, y + 10, bw, 5, ui.theme.control);
			Draw.rect(ui.g, bx, y + 10, Math.round(bw * f), 5, c);
		}
	}
}
