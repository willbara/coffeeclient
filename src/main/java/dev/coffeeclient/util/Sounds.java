package dev.coffeeclient.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import java.util.List;

/** Local (client-only) UI sounds by id. */
public final class Sounds {
	private static List<String> ids;

	private Sounds() {
	}

	public static List<String> allIds() {
		if (ids == null) ids = BuiltInRegistries.SOUND_EVENT.keySet().stream().map(Identifier::toString).sorted().toList();
		return ids;
	}

	/** "entity.experience_orb.pickup" style label (namespace dropped for vanilla sounds). */
	public static String label(String id) {
		return id.startsWith("minecraft:") ? id.substring(10) : id;
	}

	public static void play(String id, float pitch, float volume) {
		if (id == null) return;
		Identifier key = Identifier.tryParse(id);
		if (key == null) return;
		SoundEvent event = BuiltInRegistries.SOUND_EVENT.getValue(key);
		if (event == null) return;
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, volume));
	}
}
