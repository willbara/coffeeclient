package dev.coffeeclient.module.modules.render;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.NumberSetting;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.List;

/** Caps how many particles spawn per tick and hides particle types you pick. */
public final class ParticleLimiter extends Module {
	public final NumberSetting maxPerTick = setting(new NumberSetting("Max per tick", "New particles allowed each tick (0 = no cap)", 200, 0, 2000, 10));
	public final IdListSetting hidden = setting(new IdListSetting("Hidden particles", "Particle types that never spawn",
		List.of("minecraft:explosion_emitter"), ParticleLimiter::allTypes, false));

	private int thisTick;
	private int blocked;

	public ParticleLimiter() {
		super("ParticleLimiter", Category.RENDER, "Caps the particle count per tick and hides chosen types.");
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return hidden;
	}

	private static List<String> allTypes() {
		return BuiltInRegistries.PARTICLE_TYPE.keySet().stream().map(Identifier::toString).sorted().toList();
	}

	@Override
	public void onTick() {
		thisTick = 0;
	}

	/** Mixin hook: false = drop this particle. */
	public static boolean allow(ParticleOptions options) {
		if (!ModuleManager.on(ParticleLimiter.class)) return true;
		ParticleLimiter p = CoffeeClient.modules().get(ParticleLimiter.class);
		Identifier id = BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType());
		if (p.hidden.contains(id)) {
			p.blocked++;
			return false;
		}
		int cap = p.maxPerTick.intValue();
		if (cap > 0 && ++p.thisTick > cap) {
			p.blocked++;
			return false;
		}
		return true;
	}
}
