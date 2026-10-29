package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.vertex.QuadInstance;
import dev.coffeeclient.module.modules.render.XRay;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * XRay on the vanilla chunk mesher: hidden blocks are skipped, see-through ones get
 * their alpha lowered and move to the translucent layer, and listed blocks keep faces that face a
 * hidden neighbour. Runs on chunk-build threads.
 */
@Mixin(ModelBlockRenderer.class)
abstract class ModelBlockRendererMixin {
	@Shadow
	@Final
	private QuadInstance quadInstance;

	/** This thread's alpha for the block being meshed (-1 = untouched). */
	@Unique
	private static final ThreadLocal<int[]> coffeeclient$alpha = ThreadLocal.withInitial(() -> new int[] {-1});

	@Inject(method = {"tesselateFlat", "tesselateAmbientOcclusion"}, at = @At("HEAD"), cancellable = true)
	private void coffeeclient$xray(BlockQuadOutput output, float x, float y, float z, List<BlockStateModelPart> parts,
								   BlockAndTintGetter level, BlockState state, BlockPos pos, CallbackInfo ci) {
		int alpha = XRay.alpha(state, pos, level);
		coffeeclient$alpha.get()[0] = alpha;
		if (alpha == 0) ci.cancel();
	}

	@Inject(method = "putQuadWithTint", at = @At("HEAD"))
	private void coffeeclient$xrayAlpha(BlockQuadOutput output, float x, float y, float z, BlockAndTintGetter level,
										BlockState state, BlockPos pos, BakedQuad quad, CallbackInfo ci) {
		int alpha = coffeeclient$alpha.get()[0];
		if (alpha != -1) quadInstance.multiplyColor(ARGB.color(alpha, 255, 255, 255));
	}

	@ModifyArg(method = "putQuadWithTint", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/block/BlockQuadOutput;put(FFFLnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"), index = 3)
	private BakedQuad coffeeclient$xrayLayer(BakedQuad quad) {
		int alpha = coffeeclient$alpha.get()[0];
		if (alpha <= 0 || alpha >= 255) return quad;
		BakedQuad.MaterialInfo info = quad.materialInfo();
		if (info.layer() == ChunkSectionLayer.TRANSLUCENT) return quad;
		BakedQuad.MaterialInfo translucent = new BakedQuad.MaterialInfo(info.sprite(), ChunkSectionLayer.TRANSLUCENT,
			info.itemRenderType(), info.tintIndex(), info.shade(), info.lightEmission());
		return new BakedQuad(quad.position0(), quad.position1(), quad.position2(), quad.position3(),
			quad.packedUV0(), quad.packedUV1(), quad.packedUV2(), quad.packedUV3(), quad.direction(), translucent);
	}

	@ModifyReturnValue(method = "shouldRenderFace", at = @At("RETURN"))
	private boolean coffeeclient$xrayFace(boolean original, BlockAndTintGetter level, BlockState state, Direction direction, BlockPos neighborPos) {
		if (!XRay.active()) return original;
		return XRay.drawSide(state, level, neighborPos.relative(direction.getOpposite()), direction, original);
	}
}
