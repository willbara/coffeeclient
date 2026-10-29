package dev.coffeeclient.module.modules.client;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.screen.CoffeeTitleScreen;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.Labeled;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Replaces the vanilla title screen with Coffee's animated one, or leaves the vanilla one when Menu is
 * Vanilla. The grass block button on Coffee's menu switches Menu to Vanilla; set it back to Coffee here.
 */
public final class MainMenu extends Module {
	public enum Menu implements Labeled {
		COFFEE("Coffee"), VANILLA("Vanilla");

		private final String label;

		Menu(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public final EnumSetting<Menu> menu = setting(new EnumSetting<>("Menu", "Which title screen to show (the grass block button on Coffee's menu picks Vanilla)", Menu.COFFEE));

	public MainMenu() {
		super("MainMenu", Category.CLIENT, "Animated Coffee title screen with a pixel logo and themed backdrop.");
		restoreEnabled(true);
	}

	/** The grass block button: the vanilla menu from now on, until Menu is set back to Coffee. */
	public static void useVanilla() {
		CoffeeClient.modules().get(MainMenu.class).menu.set(Menu.VANILLA);
	}

	/** Called for every screen about to open: swaps the vanilla title screen for ours. */
	public static Screen replace(Screen screen) {
		if (screen == null || screen.getClass() != TitleScreen.class) return screen;
		MainMenu m = ModuleManager.ifOn(MainMenu.class);
		return m == null || m.menu.get() == Menu.VANILLA ? screen : new CoffeeTitleScreen();
	}

	@Override
	public String infoString() {
		return menu.get().label();
	}
}
