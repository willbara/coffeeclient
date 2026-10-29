package dev.coffeeclient.mixin;

import dev.coffeeclient.module.modules.render.Ambience;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ambience: choose which skybox is drawn (vanilla, End, or none). */
@Mixin(SkyRenderer.class)
abstract class SkyRendererMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void coffeeclient$skybox(ClientLevel level, float partialTicks, Camera camera, SkyRenderState state, CallbackInfo ci) {
		state.skybox = Ambience.skybox(state.skybox);
	}
}
