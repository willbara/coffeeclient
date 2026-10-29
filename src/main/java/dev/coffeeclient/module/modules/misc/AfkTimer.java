package dev.coffeeclient.module.modules.misc;

import dev.coffeeclient.core.EventBus;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.ModuleHudElement;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Sounds;

import java.util.List;

/** Tracks how long you've been idle (no keys, mouse look or movement). */
public final class AfkTimer extends Module {
	public final NumberSetting idleAfter = setting(new NumberSetting("Idle after (s)", "Seconds without input before you're AFK", 60, 10, 900, 10));
	public final BooleanSetting countMovement = setting(new BooleanSetting("Movement counts", "Being moved (e.g. by water) resets the timer", false));
	public final BooleanSetting notifyAfk = setting(new BooleanSetting("Toast on AFK", "Toast when you go AFK and come back", true));
	public final BooleanSetting sound = setting(new BooleanSetting("Sound on return", "Ping when you come back", false));
	public final BooleanSetting alwaysShow = setting(new BooleanSetting("Always show", "Show idle time even before you're AFK", false));

	private long lastInput = System.currentTimeMillis();
	private float lastYaw, lastPitch;
	private double lastX, lastZ;
	private boolean afk;

	public AfkTimer() {
		super("AfkTimer", Category.MISC, "Tracks how long you have been idle.");
	}

	@Override
	protected void init(EventBus bus) {
		bus.subscribe(Events.KeyPress.class, e -> active());
	}

	private void active() {
		long now = System.currentTimeMillis();
		if (afk && isEnabled()) {
			if (notifyAfk.get()) notify("Back after " + format(now - lastInput));
			if (sound.get()) Sounds.play("minecraft:block.note_block.bell", 1.4f, 0.6f);
		}
		afk = false;
		lastInput = now;
	}

	@Override
	public void onTick() {
		var p = mc().player;
		if (p == null) return;
		boolean looked = Math.abs(p.getYRot() - lastYaw) > 0.01f || Math.abs(p.getXRot() - lastPitch) > 0.01f;
		boolean moved = countMovement.get() && (Math.abs(p.getX() - lastX) > 0.01 || Math.abs(p.getZ() - lastZ) > 0.01);
		if (looked || moved) active();
		lastYaw = p.getYRot();
		lastPitch = p.getXRot();
		lastX = p.getX();
		lastZ = p.getZ();
		if (!afk && idleMs() > idleAfter.get() * 1000) {
			afk = true;
			if (notifyAfk.get()) notify("You're AFK");
		}
	}

	private long idleMs() {
		return System.currentTimeMillis() - lastInput;
	}

	private static String format(long ms) {
		long s = ms / 1000;
		return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
	}

	@Override
	public List<HudElement> hudElements() {
		return List.of(new ModuleHudElement(this, "afk", "AFK timer",
			() -> List.<String[]>of(ModuleHudElement.row(afk ? "AFK" : "Idle", format(idleMs()))),
			(w, h) -> new int[] {w / 2 - 30, 40}).when(() -> afk || alwaysShow.get()).fixed());
	}
}
