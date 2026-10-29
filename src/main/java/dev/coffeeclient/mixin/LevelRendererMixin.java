package dev.coffeeclient.mixin;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleProfiler;
import dev.coffeeclient.render.EspRender;
import dev.coffeeclient.render.OverlayRenderer;
import dev.coffeeclient.util.ErrorGuard;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** SelectOutline: hides the vanilla selection outline; modules: world-space overlays (StorageESP, NewChunks, ...). */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
	@Inject(method = "submitBlockOutline", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$replaceOutline(CallbackInfo ci) {
		if (dev.coffeeclient.module.modules.render.SelectOutline.replacesVanilla()) ci.cancel();
	}

	/* Collected at the head of feature submission, while the level's model-view is current; the
	   geometry is uploaded here and drawn after the level (GameRendererMixin). */
	@Inject(method = "submitFeatures", at = @At("HEAD"))
	private void coffeeclient$moduleWorld(LevelRenderState levelRenderState, SubmitNodeCollector collector, boolean renderBlockOutline, CallbackInfo ci) {
		if (levelRenderState == null || levelRenderState.cameraRenderState == null) return;
		Vec3 cam = levelRenderState.cameraRenderState.pos;
		dev.coffeeclient.module.modules.render.SelectOutline.vanillaOutline(renderBlockOutline);
		OverlayRenderer.begin();
		EspRender.beginFrame();
		PoseStack pose = new PoseStack();
		boolean profiling = ModuleProfiler.active();
		for (Module module : CoffeeClient.modules().all()) {
			if (!module.isEnabled()) continue;
			// Shared and reset per module, so one that throws or forgets a pop cannot skew the next.
			if (!pose.isEmpty()) pose = new PoseStack();
			pose.setIdentity();
			long start = profiling ? System.nanoTime() : 0;
			try {
				module.renderWorld(pose, cam);
			} catch (Throwable t) {
				ErrorGuard.report(module.worldKey, t);
			}
			if (profiling) ModuleProfiler.record(module, ModuleProfiler.Phase.RENDER, System.nanoTime() - start);
		}
		OverlayRenderer.end();
	}

	/** NoEffect: no cracks on blocks being broken. */
	@Inject(method = "submitBlockDestroyAnimation", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$noBreakCracks(PoseStack poseStack, SubmitNodeCollector collector, LevelRenderState levelRenderState, CallbackInfo ci) {
		if (dev.coffeeclient.module.modules.render.NoEffect.hides(m -> m.breakOverlay)) ci.cancel();
	}
}
