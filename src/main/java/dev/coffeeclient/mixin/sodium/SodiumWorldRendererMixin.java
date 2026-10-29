package dev.coffeeclient.mixin.sodium;

import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.render.Freecam;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Freecam and XRay: Sodium culls by its own occlusion graph, so turn that off while detached or x-raying. */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
abstract class SodiumWorldRendererMixin {
	@ModifyVariable(method = "setupTerrain", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private boolean coffeeclient$occlusionCulling(boolean useOcclusionCulling) {
		return useOcclusionCulling && !ModuleManager.on(Freecam.class) && !dev.coffeeclient.module.modules.render.XRay.active()
			&& !dev.coffeeclient.module.modules.render.NoEffect.hides(m -> m.caveCulling);
	}
}
