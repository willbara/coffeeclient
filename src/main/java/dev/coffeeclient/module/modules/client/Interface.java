package dev.coffeeclient.module.modules.client;

import dev.coffeeclient.gui.screen.SettingsScreen;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import net.minecraft.client.gui.screens.Screen;

/** ClickGUI shortcut to the Settings screen, where every interface option lives. */
public final class Interface extends Module {
	public Interface() {
		super("Interface", Category.CLIENT, "Opens the Settings screen: accent, effects, motion and window options for every client screen.");
	}

	@Override
	public boolean isToggleable() {
		return false;
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public Screen configScreen(Screen parent) {
		return new SettingsScreen(parent);
	}
}
