package dev.coffeeclient.module.modules.misc;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.StringListSetting;

import java.util.ArrayList;
import java.util.List;

/**
 * One key turns every module off; turning Panic off again brings back exactly the ones that were on.
 * The list of switched-off modules is saved with the config, so it
 * survives a restart while Panic stays on. Modules are switched without chat lines or toasts.
 */
public final class Panic extends Module {
	public final BooleanSetting clientModules = setting(new BooleanSetting("Client modules too",
		"Also switch off the HUD, notifications and the other Client modules", false));
	/** Names of the modules Panic switched off; not shown, only saved. */
	private final StringListSetting saved = setting(new StringListSetting("Saved", "Modules to switch back on", List.of(), 64)
		.visibleWhen(() -> false));

	public Panic() {
		super("Panic", List.of("Disable all", "Legit"), Category.MISC, "Switches every module off; switch it off to restore them.");
		// Panicking should not announce itself in chat; the switched modules stay quiet too.
		chatFeedback(false);
	}

	@Override
	protected void onEnable() {
		// Re-enabling while a snapshot is pending (restart with Panic on) keeps the old snapshot.
		List<String> names = new ArrayList<>(saved.get());
		for (Module m : CoffeeClient.modules().all()) {
			if (m == this || !m.isEnabled() || !m.isToggleable() || m.isAction()) continue;
			if (m.category() == Category.CLIENT && !clientModules.get()) continue;
			if (!names.contains(m.name())) names.add(m.name());
			m.setEnabledSilently(false);
		}
		saved.set(List.copyOf(names));
	}

	@Override
	protected void onDisable() {
		int restored = 0;
		for (String name : saved.get()) {
			var module = CoffeeClient.modules().byName(name);
			if (module.isPresent() && !module.get().isEnabled()) {
				module.get().setEnabledSilently(true);
				restored++;
			}
		}
		saved.set(List.of());
		if (restored > 0) info("Restored " + restored + " module" + (restored == 1 ? "" : "s"));
	}

	@Override
	public String infoString() {
		return saved.get().isEmpty() ? null : Integer.toString(saved.get().size());
	}
}
