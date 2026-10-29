package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.ModuleCustomElement;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Colors;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Lists items entering (and optionally leaving) your inventory, like a pickup feed. */
public final class PickupNotifier extends Module {
	public final NumberSetting duration = setting(new NumberSetting("Duration (s)", "How long each entry stays", 3, 1, 10, 0.5));
	public final NumberSetting maxEntries = setting(new NumberSetting("Max entries", "Most lines shown at once", 6, 1, 15, 1));
	public final BooleanSetting merge = setting(new BooleanSetting("Merge", "Combine repeated pickups of the same item", true));
	public final BooleanSetting losses = setting(new BooleanSetting("Show losses", "Also list items that leave your inventory", false));
	public final BooleanSetting ignoreInScreens = setting(new BooleanSetting("Ignore in menus", "Don't count moving items around in containers", true));

	private static final class Entry {
		final ItemStack icon;
		int amount;
		long time;

		Entry(ItemStack icon, int amount, long time) {
			this.icon = icon;
			this.amount = amount;
			this.time = time;
		}
	}

	private final List<Entry> entries = new ArrayList<>();
	private Map<Item, Integer> last = null;
	private final HudElement element = new Element();

	public PickupNotifier() {
		super("PickupNotifier", Category.PLAYER, "Lists items you pick up.");
	}

	@Override
	public List<HudElement> hudElements() {
		return List.of(element);
	}

	@Override
	public void onTick() {
		var mc = mc();
		if (mc.player == null) {
			last = null;
			return;
		}
		Map<Item, Integer> now = new HashMap<>();
		Map<Item, ItemStack> icons = new HashMap<>();
		for (ItemStack s : mc.player.getInventory()) {
			if (s.isEmpty()) continue;
			now.merge(s.getItem(), s.getCount(), Integer::sum);
			icons.putIfAbsent(s.getItem(), s);
		}
		boolean skip = last == null || (ignoreInScreens.get() && mc.gui.screen() != null);
		if (!skip) {
			long t = System.currentTimeMillis();
			for (var e : now.entrySet()) {
				int diff = e.getValue() - last.getOrDefault(e.getKey(), 0);
				if (diff > 0) add(icons.get(e.getKey()), diff, t);
			}
			if (losses.get()) {
				for (var e : last.entrySet()) {
					int diff = now.getOrDefault(e.getKey(), 0) - e.getValue();
					if (diff < 0) add(new ItemStack(e.getKey()), diff, t);
				}
			}
		}
		last = now;
		long cutoff = System.currentTimeMillis() - Math.round(duration.get() * 1000);
		entries.removeIf(e -> e.time < cutoff);
		while (entries.size() > maxEntries.intValue()) entries.removeFirst();
	}

	private void add(ItemStack icon, int amount, long time) {
		if (merge.get()) {
			for (Entry e : entries) {
				if (ItemStack.isSameItem(e.icon, icon) && Integer.signum(e.amount) == Integer.signum(amount)) {
					e.amount += amount;
					e.time = time;
					return;
				}
			}
		}
		entries.add(new Entry(icon.copyWithCount(1), amount, time));
	}

	private final class Element extends ModuleCustomElement {
		Element() {
			super(PickupNotifier.this, "pickups", "Pickup feed");
		}

		@Override
		public boolean shouldRender() {
			return !entries.isEmpty();
		}

		@Override
		public int width() {
			return 120;
		}

		@Override
		public int height() {
			return Math.max(1, entries.size()) * 18;
		}

		@Override
		protected int[] defaultPosition(int w, int h) {
			return new int[] {w - 122, h / 2 + 20};
		}

		@Override
		public void render(Ui ui, int x, int y, boolean preview) {
			long now = System.currentTimeMillis();
			long life = Math.round(duration.get() * 1000);
			int ly = y;
			for (Entry e : entries) {
				float a = Math.min(1f, (life - (now - e.time)) / 400f);
				Draw.rect(ui.g, x, ly, width(), 17, Colors.fade(ui.theme.panel, Math.max(0.05f, a)));
				ui.g.item(e.icon, x + 1, ly);
				String text = (e.amount > 0 ? "+" : "") + e.amount + " " + e.icon.getHoverName().getString();
				int color = e.amount > 0 ? ui.theme.text : ui.theme.error;
				Draw.text(ui.g, Draw.trim(text, width() - 22), x + 20, ly + 5, Colors.fade(color, Math.max(0.05f, a)));
				ly += 18;
			}
			if (entries.isEmpty()) Draw.text(ui.g, "+1 Example", x + 20, y + 5, ui.theme.dimText);
		}
	}
}
