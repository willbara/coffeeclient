package dev.coffeeclient.mixin;

import dev.coffeeclient.module.modules.render.NoEffect;
import net.minecraft.client.renderer.WorldBorderRenderer;
import net.minecraft.client.renderer.state.level.WorldBorderRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NoEffect: no world border wall. */
@Mixin(WorldBorderRenderer.class)
abstract class WorldBorderRendererMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$noWorldBorder(WorldBorderRenderState state, Vec3 cameraPos, double renderDistance, double depthFar, CallbackInfo ci) {
		if (NoEffect.hides(m -> m.worldBorder)) ci.cancel();
	}
}
