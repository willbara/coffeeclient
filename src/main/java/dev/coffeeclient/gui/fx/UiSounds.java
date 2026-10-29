package dev.coffeeclient.gui.fx;

import dev.coffeeclient.core.CoffeeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/** Quiet interface sounds, played only while the theme's UI sounds option is on. */
public final class UiSounds {
	private static long lastNanos;

	private UiSounds() {
	}

	private static boolean on() {
		var theme = CoffeeClient.theme();
		if (theme == null || !theme.uiSounds.get()) return false;
		// a burst of clicks in one frame (e.g. a drag) plays once
		long now = System.nanoTime();
		if (now - lastNanos < 40_000_000L) return false;
		lastNanos = now;
		return true;
	}

	public static void click(float pitch) {
		if (on()) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 0.25f));
	}

	/** Module toggled: a higher tick for on, lower for off. */
	public static void toggle(boolean enabled) {
		if (on()) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), enabled ? 1.6f : 1.1f, 0.35f));
	}

	public static void open() {
		if (on()) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.5f, 0.18f));
	}
}
