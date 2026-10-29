package dev.coffeeclient.mixin;

import dev.coffeeclient.module.modules.render.Brightness;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Brightness module: adjust the extracted lightmap inputs (gamma or night-vision intensity). */
@Mixin(LightmapRenderStateExtractor.class)
abstract class LightmapMixin {
	@Inject(method = "extract", at = @At("TAIL"))
	private void coffeeclient$brightness(LightmapRenderState state, float partialTicks, CallbackInfo ci) {
		if (!Brightness.active()) return;
		Brightness b = Brightness.self();
		if (b.mode.get() == Brightness.Mode.GAMMA) state.brightness = Math.max(state.brightness, b.gamma.floatValue());
		else state.nightVisionEffectIntensity = Math.max(state.nightVisionEffectIntensity, b.strength.floatValue());
	}
}
