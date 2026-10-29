package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.coffeeclient.module.modules.render.XRay;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** XRay on vanilla fluid meshing: see-through or hidden water and lava. Chunk-build threads. */
@Mixin(FluidRenderer.class)
abstract class FluidRendererMixin {
	/** This thread's alpha for the fluid being meshed (-1 = untouched). */
	@Unique
	private static final ThreadLocal<int[]> coffeeclient$alpha = ThreadLocal.withInitial(() -> new int[] {-1});

	@Inject(method = "tesselate", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$xray(BlockAndTintGetter level, BlockPos pos, FluidRenderer.Output output, BlockState blockState,
								   FluidState fluidState, CallbackInfo ci) {
		int alpha = XRay.fluidAlpha(fluidState, pos, level);
		coffeeclient$alpha.get()[0] = alpha;
		if (alpha == 0) ci.cancel();
	}

	/** Sides against a hidden block would otherwise stay culled, leaving holes in the water. */
	@WrapOperation(method = "tesselate", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/block/FluidRenderer;isFaceOccludedByNeighbor(Lnet/minecraft/core/Direction;FLnet/minecraft/world/level/block/state/BlockState;)Z"))
	private boolean coffeeclient$xraySides(Direction direction, float height, BlockState neighbor, Operation<Boolean> original) {
		boolean occluded = original.call(direction, height, neighbor);
		if (!occluded || direction.getAxis().isVertical() || !XRay.active()) return occluded;
		return !XRay.blocked(neighbor.getBlock(), null, null);
	}

	@ModifyVariable(method = "vertex", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int coffeeclient$xrayColor(int color) {
		int alpha = coffeeclient$alpha.get()[0];
		return alpha == -1 ? color : (alpha << 24) | (color & 0xFFFFFF);
	}

	@ModifyArg(method = "tesselate", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/block/FluidRenderer$Output;getBuilder(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
	private ChunkSectionLayer coffeeclient$xrayLayer(ChunkSectionLayer layer) {
		int alpha = coffeeclient$alpha.get()[0];
		return alpha > 0 && alpha < 255 ? ChunkSectionLayer.TRANSLUCENT : layer;
	}
}
