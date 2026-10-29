package dev.coffeeclient.module.modules.misc;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.util.Setback;

import java.util.ArrayList;
import java.util.List;

/**
 * Switches chosen modules off when something goes wrong: the server sets you back, you die, you change
 * world or you disconnect. After AutoDisable. (Notifications' "Disable on setback"
 * instead switches off whichever movement modules were on; this one switches off a fixed list, for
 * every reason picked.)
 */
public final class AutoDisable extends Module {
	public final IdListSetting modules = setting(new IdListSetting("Modules", "Modules to switch off",
		List.of("Flight", "Speed", "KillAura", "CrystalAura", "Blink"), AutoDisable::moduleNames, false));
	public final BooleanSetting onSetback = setting(new BooleanSetting("On setback", "When the server corrects your position", true));
	public final BooleanSetting onDeath = setting(new BooleanSetting("On death", "When you die", true));
	public final BooleanSetting onWorldChange = setting(new BooleanSetting("On world change", "When you join a server or change dimension", true));
	public final BooleanSetting onDisconnect = setting(new BooleanSetting("On disconnect", "When you leave a server", true));

	private boolean wasDead;

	public AutoDisable() {
		super("AutoDisable", List.of("Auto Disable"), Category.MISC, "Switches chosen modules off on setbacks, death, world changes and disconnects.");
		// Low: Setback has already decided whether this teleport was a correction.
		listen(Events.ServerTeleport.class, EventBus.LOW, e -> {
			if (onSetback.get() && Setback.recent(100)) disable("setback");
		});
		listen(Events.WorldChange.class, e -> {
			wasDead = false;
			if (onWorldChange.get()) disable("world change");
		});
		listen(Events.GameLeft.class, e -> {
			if (onDisconnect.get()) disable("disconnect");
		});
	}

	@Override
	public void onTick() {
		var player = mc().player;
		boolean dead = player != null && player.isDeadOrDying();
		if (dead && !wasDead && onDeath.get()) disable("death");
		wasDead = dead;
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return modules;
	}

	private void disable(String reason) {
		List<String> off = new ArrayList<>();
		for (Module module : CoffeeClient.modules().all()) {
			if (module != this && module.isEnabled() && modules.contains(module.name())) {
				module.setEnabled(false);
				off.add(module.name());
			}
		}
		if (off.isEmpty()) return;
		CoffeeClient.LOG.info("[AutoDisable] {} switched off after {}", off, reason);
		if (mc().player != null) info("Switched off " + String.join(", ", off) + " (" + reason + ")");
	}

	/** Every module that can stay on (the picker's options). */
	private static List<String> moduleNames() {
		List<String> names = new ArrayList<>();
		for (Module module : CoffeeClient.modules().all()) {
			if (module.isToggleable() && !module.isAction() && !(module instanceof AutoDisable)) names.add(module.name());
		}
		names.sort(String.CASE_INSENSITIVE_ORDER);
		return names;
	}
}
