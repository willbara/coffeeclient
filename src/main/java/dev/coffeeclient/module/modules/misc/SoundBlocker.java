package dev.coffeeclient.module.modules.misc;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.IdListSetting;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import java.util.List;

/** Blocks chosen sounds. */
public final class SoundBlocker extends Module {
	public final IdListSetting sounds = setting(new IdListSetting("Sounds", "Sounds to block", List.of(),
		SoundBlocker::allSounds, false).labels(SoundBlocker::soundName));

	public SoundBlocker() {
		super("SoundBlocker", Category.WORLD, "Cancels out selected sounds.");
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return sounds;
	}

	/** Called from {@code SoundEngineMixin}. */
	public static boolean shouldBlock(SoundInstance instance) {
		if (dev.coffeeclient.module.modules.movement.ElytraFly.mutesSound(instance.getIdentifier())) return true;
		if (!ModuleManager.on(SoundBlocker.class)) return false;
		Identifier id = instance.getIdentifier();
		return CoffeeClient.modules().get(SoundBlocker.class).sounds.contains(id);
	}

	private static List<String> allSounds() {
		return BuiltInRegistries.SOUND_EVENT.keySet().stream().map(Identifier::toString).sorted().toList();
	}

	private static String soundName(String id) {
		Identifier key = Identifier.tryParse(id);
		if (key == null) return id;
		SoundEvent event = BuiltInRegistries.SOUND_EVENT.getOptional(key).orElse(null);
		return event == null ? id : event.location().toString();
	}
}
