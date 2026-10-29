package dev.coffeeclient.module.modules.render;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Merges boss bars with the same title into one bar with a counter, and adjusts their spacing. */
public final class BossStack extends Module {
	public final BooleanSetting stack = setting(new BooleanSetting("Stack", "Stack boss bars with the same title", true));
	public final BooleanSetting hideName = setting(new BooleanSetting("Hide names", "Hide boss bar titles", false));
	/** Total vertical distance between bars, in pixels (vanilla is 19). */
	public final NumberSetting spacing = group(new NumberSetting("Bar spacing", "Vertical distance between bars (vanilla 19)", 19, 0, 40, 1), "General");

	private static final Map<LerpingBossEvent, Integer> COUNTS = new WeakHashMap<>();

	public BossStack() {
		super("BossStack", java.util.List.of("Boss Stack"), Category.RENDER, "Stacks identical boss bars and tightens their spacing.");
	}

	private static BossStack instance() {
		if (CoffeeClient.modules() == null) return null;
		try {
			return CoffeeClient.modules().get(BossStack.class);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Called by the BossHealthOverlay mixin with the vanilla iterator. */
	public static Iterator<LerpingBossEvent> stackBars(Iterator<LerpingBossEvent> original) {
		BossStack module = instance();
		if (module == null || !module.isEnabled() || !module.stack.get()) {
			COUNTS.clear();
			return original;
		}
		Map<String, LerpingBossEvent> first = new LinkedHashMap<>();
		Map<LerpingBossEvent, Integer> counts = new IdentityHashMap<>();
		while (original.hasNext()) {
			LerpingBossEvent bar = original.next();
			String name = bar.getName().getString();
			LerpingBossEvent existing = first.putIfAbsent(name, bar);
			if (existing == null) counts.put(bar, 1);
			else counts.merge(existing, 1, Integer::sum);
		}
		COUNTS.clear();
		COUNTS.putAll(counts);
		return first.values().iterator();
	}

	/** Called by the mixin for each drawn bar. */
	public static Component barName(LerpingBossEvent bar, Component original) {
		BossStack module = instance();
		if (module == null || !module.isEnabled()) return original;
		if (module.hideName.get()) return Component.empty();
		if (!module.stack.get()) return original;
		Integer count = COUNTS.get(bar);
		return count == null || count <= 1 ? original : original.copy().append(Component.literal(" x" + count));
	}

	/** Called by the mixin for the vanilla spacing constant (9); the total gap is 10 + this value. */
	public static int barSpacing(int vanilla) {
		BossStack module = instance();
		if (module == null || !module.isEnabled()) return vanilla;
		return Math.max(0, module.spacing.intValue() - 10);
	}
}
