package dev.coffeeclient.mixin;

import dev.coffeeclient.module.modules.render.ParticleLimiter;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** ParticleLimiter: drop hidden or over-budget particles before they are created. */
@Mixin(ParticleEngine.class)
abstract class ParticleEngineMixin {
	@Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$limit(ParticleOptions options, double x, double y, double z, double xa, double ya, double za,
									CallbackInfoReturnable<Particle> cir) {
		if (dev.coffeeclient.module.modules.render.Ambience.blocksParticle(options) || !ParticleLimiter.allow(options)) cir.setReturnValue(null);
	}
}
