package dev.coffeeclient.mixin.sodium;

import dev.coffeeclient.module.modules.render.XRay;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** XRay opacity on Sodium's block mesher. One renderer per build thread. */
@Mixin(value = BlockRenderer.class, remap = false)
abstract class SodiumBlockRendererMixin {
	@Unique
	private int coffeeclient$alpha = -1;

	@Inject(method = "renderModel", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$xray(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin, CallbackInfo ci) {
		coffeeclient$alpha = XRay.alpha(state, pos, null);
		if (coffeeclient$alpha == 0) ci.cancel();
	}

	@Inject(method = "bufferQuad", at = @At("HEAD"))
	private void coffeeclient$xrayAlpha(MutableQuadViewImpl quad, float[] brightnesses, Material material, CallbackInfo ci) {
		if (coffeeclient$alpha == -1) return;
		for (int i = 0; i < 4; i++) quad.setColor(i, (coffeeclient$alpha << 24) | (quad.baseColor(i) & 0xFFFFFF));
	}

	@ModifyArg(method = "processQuad", at = @At(value = "INVOKE",
		target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer;bufferQuad(Lnet/caffeinemc/mods/sodium/client/render/model/MutableQuadViewImpl;[FLnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/Material;)V"),
		index = 2)
	private Material coffeeclient$xrayMaterial(Material material) {
		return coffeeclient$alpha != -1 ? DefaultMaterials.TRANSLUCENT : material;
	}
}
