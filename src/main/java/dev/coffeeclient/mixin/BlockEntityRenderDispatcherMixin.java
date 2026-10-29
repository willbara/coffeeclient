package dev.coffeeclient.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.module.modules.render.XRay;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** XRay: block entities (chests, signs, ...) that are not listed are not drawn while the rest is hidden. NoEffect: beacon beams, banners, the enchanting book. */
@Mixin(BlockEntityRenderDispatcher.class)
abstract class BlockEntityRenderDispatcherMixin {
	@Inject(method = "submit", at = @At("HEAD"), cancellable = true)
	private <S extends BlockEntityRenderState> void coffeeclient$xray(S state, PoseStack poseStack, SubmitNodeCollector collector,
																	   CameraRenderState camera, CallbackInfo ci) {
		var blockState = ((BlockEntityRenderStateAccessor) state).coffeeclient$blockState();
		if (dev.coffeeclient.module.modules.render.NoEffect.hidesBlockEntity(blockState)) {
			ci.cancel();
			return;
		}
		if (!XRay.active()) return;
		if (XRay.alpha(blockState, state.blockPos, null) == 0) ci.cancel();
	}
}
