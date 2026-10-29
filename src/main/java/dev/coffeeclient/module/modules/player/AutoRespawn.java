package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.StringListSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Respawns you automatically. While it's off, the death screen can show
 * a button that turns it on. With a delay the death screen stays up for that
 * long first. Commands run one by one after you are back (for example {@code /home}).
 */
public final class AutoRespawn extends Module {
	public final BooleanSetting deathButton = setting(new BooleanSetting("Death screen button", "While AutoRespawn is off, show a button on the death screen that turns it on and respawns", true));
	public final NumberSetting delay = setting(new NumberSetting("Delay", "Ticks to wait on the death screen before respawning", 0, 0, 200, 1));
	public final StringListSetting commands = setting(new StringListSetting("Commands", "Run after respawning, in order (e.g. /home)", List.of(), 256));
	public final NumberSetting commandDelay = setting(new NumberSetting("Command delay", "Ticks between respawning and each command", 20, 1, 200, 1));

	/** Ticks until the delayed respawn, or -1. */
	private int respawnIn = -1;
	private final ArrayDeque<String> pending = new ArrayDeque<>();
	private int commandIn;

	public AutoRespawn() {
		super("AutoRespawn", Category.PLAYER, "Automatically respawns after death.");
	}

	/** Whether the death screen should offer the button. Called from the death screen mixin. */
	public static boolean showButton() {
		AutoRespawn self = CoffeeClient.modules().get(AutoRespawn.class);
		return self != null && !self.isEnabled() && self.deathButton.get();
	}

	/** The button: turn on and respawn now. */
	public static void enableAndRespawn() {
		AutoRespawn self = CoffeeClient.modules().get(AutoRespawn.class);
		self.setEnabled(true);
		var mc = Minecraft.getInstance();
		self.respawn();
		mc.gui.setScreen(null);
	}

	@Override
	protected void init(EventBus bus) {
		listen(Events.OpenScreen.class, event -> {
			if (!(event.screen instanceof DeathScreen)) return;
			if (delay.get() > 0) {
				if (respawnIn < 0) respawnIn = delay.intValue();
				return;
			}
			respawn();
			event.cancel();
		});
	}

	@Override
	protected void onDisable() {
		respawnIn = -1;
		pending.clear();
	}

	@Override
	public void onTick() {
		if (respawnIn >= 0 && respawnIn-- == 0) {
			respawn();
			if (mc().gui.screen() instanceof DeathScreen) mc().gui.setScreen(null);
		}
		LocalPlayer player = mc().player;
		if (pending.isEmpty() || player == null || !player.isAlive() || --commandIn > 0) return;
		String command = pending.poll();
		player.connection.sendCommand(command.startsWith("/") ? command.substring(1) : command);
		commandIn = commandDelay.intValue();
	}

	private void respawn() {
		respawnIn = -1;
		LocalPlayer player = mc().player;
		if (player == null) return;
		player.respawn();
		pending.clear();
		for (String c : commands.get()) if (!c.isBlank()) pending.add(c.trim());
		commandIn = commandDelay.intValue();
	}
}
