package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.coffeeclient.module.modules.render.NoEffect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.environment.MobEffectFogEnvironment;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** NoEffect: drops the blindness and darkness fog for the local player. */
@Mixin(MobEffectFogEnvironment.class)
abstract class MobEffectFogEnvironmentMixin {
	@Shadow
	public abstract Holder<MobEffect> getMobEffect();

	@ModifyReturnValue(method = "isApplicable", at = @At("RETURN"))
	private boolean coffeeclient$noEffectFog(boolean original, FogType fogType, Entity entity) {
		if (!original || entity != Minecraft.getInstance().player) return original;
		return !NoEffect.hidesView(getMobEffect());
	}
}
