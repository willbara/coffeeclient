package dev.coffeeclient.mixin;

import dev.coffeeclient.module.modules.render.NoEffect;
import dev.coffeeclient.render.OverlayRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flushes the world-space overlays (StorageESP, NewChunks, ...) collected during feature
 * submission. Drawn right after the level: its colour and depth are complete by then and the level
 * projection is still bound, so the overlays end up on top of translucent terrain (water) instead of
 * being blended over by it. Also NoEffect's hurt cam option.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
		target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"))
	private void coffeeclient$worldOverlays(DeltaTracker deltaTracker, CallbackInfo ci) {
		OverlayRenderer.draw();
	}

	@Inject(method = "displayItemActivation", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$noTotem(net.minecraft.world.item.ItemStack stack, CallbackInfo ci) {
		if (NoEffect.hides(m -> m.totem)) ci.cancel();
	}

	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$noHurtCam(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
		if (NoEffect.hidesHurtCam()) ci.cancel();
	}
}
