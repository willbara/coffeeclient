package dev.coffeeclient.config;

import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;

import java.util.List;
import java.util.Locale;

/** "Mod options" shown in the Settings screen. */
public final class ClientOptions {
	/** Size of every client screen and HUD relative to the game's GUI scale, shared by every theme. */
	public final NumberSetting interfaceScale = new NumberSetting("Interface scale", "Size of all client screens and the HUD relative to the GUI scale", 0.72, 0.5, 2.0, 0)
		.format(v -> String.format(Locale.ROOT, "%.2fx", v));
	public final NumberSetting logLinesKept = new NumberSetting("Log lines kept", "Lines buffered by the dashboard log", 5000, 100, 50000, 100);
	/** Log panel height in dashboard units; 0 fills the remaining space. Also set by dragging the panel's bottom edge. */
	public final NumberSetting dashboardLogHeight = new NumberSetting("Dashboard log height", "Height of the dashboard log panel", 0, 0, 600, 10)
		.format(v -> v <= 0 ? "Fill" : (int) Math.round(v) + " px");

	/** Ticks the server keeps seeing a module's look direction after the module stops asking (see Rotations). */
	public final NumberSetting rotationHold = new NumberSetting("Rotation hold", "Ticks a module's server-side look is kept after it stops rotating", 4, 0, 20, 1)
		.format(v -> (int) Math.round(v) + " ticks");

	public final BooleanSetting rotationGcd = new BooleanSetting("Rotation mouse steps", "Round module turns to steps a real mouse could make at your sensitivity", false);
	public final BooleanSetting movementFix = new BooleanSetting("Movement correction",
		"While a module turns you, move along the look the server sees, with keys picked so you still go where you steer (for anti-cheats that simulate movement)", false);
	public final NumberSetting rotationJitter = new NumberSetting("Rotation jitter", "Random spread added to module turns, in degrees (0 = off)", 0, 0, 2, 0.05);

	public List<Setting<?>> all() {
		return List.of(interfaceScale, logLinesKept, dashboardLogHeight, rotationHold, rotationGcd, rotationJitter, movementFix);
	}
}
