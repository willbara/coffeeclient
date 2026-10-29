package dev.coffeeclient.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.coffeeclient.module.modules.render.XRay;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** XRay: a white lightmap, so ores deep in the dark are fully lit. */
@Mixin(Lightmap.class)
abstract class LightmapTextureMixin {
	@Shadow
	@Final
	private GpuTexture texture;

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$xrayFullbright(LightmapRenderState renderState, CallbackInfo ci) {
		if (!XRay.active()) return;
		RenderSystem.getDevice().createCommandEncoder().clearColorTexture(texture, new Vector4f(1));
		ci.cancel();
	}
}
