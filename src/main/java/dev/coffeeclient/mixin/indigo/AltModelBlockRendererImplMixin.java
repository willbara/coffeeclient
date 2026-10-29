package dev.coffeeclient.mixin.indigo;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.coffeeclient.module.modules.render.XRay;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** XRay for blocks meshed through Fabric's Indigo renderer. Chunk-build threads. */
@SuppressWarnings("UnstableApiUsage")
@Mixin(value = AltModelBlockRendererImpl.class, remap = false)
abstract class AltModelBlockRendererImplMixin {
	@Shadow
	private BlockState blockState;

	@Shadow
	private BlockPos pos;

	@Shadow
	private BlockAndTintGetter level;

	@ModifyReturnValue(method = "shouldCullFace", at = @At("RETURN"))
	private boolean coffeeclient$xrayCull(boolean original, Direction direction) {
		if (direction == null || !XRay.active()) return original;
		return !XRay.drawSide(blockState, level, pos, direction, !original);
	}

	@Inject(method = "transform", at = @At("RETURN"), cancellable = true)
	private void coffeeclient$xrayAlpha(MutableQuadView quad, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ()) return;
		int alpha = XRay.alpha(blockState, pos, level);
		if (alpha == 0) {
			cir.setReturnValue(false);
		} else if (alpha != -1) {
			if (alpha < 255) quad.chunkLayer(ChunkSectionLayer.TRANSLUCENT);
			for (int i = 0; i < 4; i++) quad.color(i, (alpha << 24) | (quad.color(i) & 0xFFFFFF));
		}
	}
}
