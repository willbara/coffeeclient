package dev.coffeeclient.module.modules.player;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.Setting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;

import java.util.List;

/** Stops potion effects ticking when you stand still. */
public final class PotionSaver extends Module {
	public final IdListSetting effects = setting(new IdListSetting("Effects", "Effects to preserve", List.of(), PotionSaver::allEffects, false).labels(PotionSaver::effectName));
	public final BooleanSetting onlyWhenStationary = setting(new BooleanSetting("Only when stationary", "Only freeze effects when you aren't moving", false));

	public PotionSaver() {
		super("PotionSaver", Category.PLAYER, "Stops potion effects ticking when you stand still.");
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return effects;
	}

	public boolean shouldFreeze(MobEffect effect) {
		if (!isEnabled()) return false;
		if (onlyWhenStationary.get()) {
			LocalPlayer p = mc().player;
			if (p == null) return false;
			var kp = p.input.keyPresses;
			if (kp.forward() || kp.backward() || kp.left() || kp.right() || p.isJumping()) return false;
		}
		if (mc().player == null || mc().player.getActiveEffects().isEmpty()) return false;
		return effects.contains(BuiltInRegistries.MOB_EFFECT.getKey(effect));
	}

	private static List<String> allEffects() {
		return BuiltInRegistries.MOB_EFFECT.keySet().stream().map(Identifier::toString).sorted().toList();
	}

	private static String effectName(String id) {
		Identifier key = Identifier.tryParse(id);
		if (key == null) return id;
		return BuiltInRegistries.MOB_EFFECT.getOptional(key).map(e -> e.getDisplayName().getString()).orElse(id);
	}
}
