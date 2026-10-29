package dev.coffeeclient.module.modules.misc;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;

/** Master switch for user-defined macros (managed in the Macros window); switching it off stops running ones. */
public final class Macros extends Module {
	public Macros() {
		super("Macros", Category.MISC, "Runs your macros: key, timer, chat and event triggers. Edit them in the Macros window.");
		restoreEnabled(true);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public net.minecraft.client.gui.screens.Screen configScreen(net.minecraft.client.gui.screens.Screen parent) {
		return new dev.coffeeclient.gui.screen.MacrosScreen(parent);
	}
}
