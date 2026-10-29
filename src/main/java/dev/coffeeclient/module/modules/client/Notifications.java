package dev.coffeeclient.module.modules.client;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;

/** Small HUD toasts when modules are toggled, and setback warnings (see {@code util.Setback}). */
public final class Notifications extends Module {
	public final NumberSetting duration = setting(new NumberSetting("Duration (s)", "How long a toast stays visible", 2.0, 0.5, 6, 0.5));
	public final BooleanSetting chatFeedback = setting(new BooleanSetting("Chat feedback", "Show chat message when toggling modules", true));
	public final BooleanSetting toastNotifications = setting(new BooleanSetting("Toast notifications", "Show toast popup when toggling modules", true));
	public final BooleanSetting setbackWarnings = setting(new BooleanSetting("Setback warnings",
		"Toast when the server pulls you back while a movement module is on", true));
	public final BooleanSetting setbackDisable = setting(new BooleanSetting("Disable on setback",
		"Turn off the movement modules that were on when the server pulls you back", false));

	public Notifications() {
		super("Notifications", Category.CLIENT, "Shows a toast when a module is switched on or off.");
		restoreEnabled(true);
	}
}
