package dev.coffeeclient.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.module.modules.render.NoEffect;
import net.minecraft.client.renderer.MapRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.MapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NoEffect: maps drawn without their markers (the render state is rebuilt every frame, so clearing it is safe). */
@Mixin(MapRenderer.class)
abstract class MapRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void coffeeclient$noMapMarkers(MapRenderState state, PoseStack poseStack, SubmitNodeCollector collector, boolean showOnlyFrame, int light, CallbackInfo ci) {
		if (NoEffect.hides(m -> m.mapMarkers)) state.decorations.clear();
	}
}
