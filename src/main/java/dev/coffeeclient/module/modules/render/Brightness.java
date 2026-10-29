package dev.coffeeclient.module.modules.render;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;

/** Fullbright: raises the lightmap gamma, or applies a night-vision style lightmap (client-side only). */
public final class Brightness extends Module {
	public enum Mode { GAMMA, NIGHT_VISION }

	public final EnumSetting<Mode> mode = setting(new EnumSetting<>("Mode", "Gamma boost or night-vision lighting", Mode.GAMMA));
	public final NumberSetting gamma = setting(new NumberSetting("Gamma", "Brightness for gamma mode (1 = vanilla max)", 8.0, 1, 16, 0.5));
	public final NumberSetting strength = setting(new NumberSetting("Night vision strength", "Intensity for night-vision mode", 1.0, 0.1, 1, 0.05));

	public Brightness() {
		super("Brightness", Category.RENDER, "Raises the minimum light level (fullbright).");
	}

	public static boolean active() {
		return ModuleManager.on(Brightness.class);
	}

	public static Brightness self() {
		return CoffeeClient.modules().get(Brightness.class);
	}
}
