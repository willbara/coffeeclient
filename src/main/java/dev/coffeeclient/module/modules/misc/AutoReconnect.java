package dev.coffeeclient.module.modules.misc;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.FlightRecorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

/**
 * Automatically reconnects when disconnected from a server. Kick report saves the last seconds
 * before each disconnect (reason, keep-alive gaps, setbacks, packets) to {@code kicks/}.
 */
public final class AutoReconnect extends Module {
	public final NumberSetting delay = setting(new NumberSetting("Delay", "Seconds to wait before reconnecting", 3.5, 0, 30, 0.5));
	public final BooleanSetting hideButtons = setting(new BooleanSetting("Hide buttons", "Hide the auto reconnect buttons", false));
	public final BooleanSetting kickReport = setting(new BooleanSetting("Kick report", "Save what happened in the last seconds before a disconnect", false));
	public final NumberSetting reportWindow = setting(new NumberSetting("Report window", "Seconds kept for the kick report", 5, 1, 30, 1).visibleWhen(kickReport::get));

	public static ServerData lastServer;

	public AutoReconnect() {
		super("AutoReconnect", Category.MISC, "Automatically reconnects when disconnected from a server.");
	}

	@Override
	public void onTick() {
		if (kickReport.get()) FlightRecorder.acquire(this, reportWindow.intValue());
		else FlightRecorder.release(this);
	}

	@Override
	protected void onDisable() {
		FlightRecorder.release(this);
	}

	public static void onJoin() {
		lastServer = Minecraft.getInstance().getCurrentServer();
	}
}
