package dev.coffeeclient.mixin.sodium;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.coffeeclient.module.modules.render.XRay;
import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.model.light.LightPipeline;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadViewMutable;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** XRay on Sodium's fluid mesher: lowered alpha, and sides shown against hidden blocks. */
@Mixin(value = DefaultFluidRenderer.class, remap = false)
abstract class SodiumDefaultFluidRendererMixin {
	@Shadow
	@Final
	private int[] quadColors;

	@Unique
	private int coffeeclient$alpha = -1;

	@Inject(method = "render", at = @At("HEAD"))
	private void coffeeclient$xray(LevelSlice level, BlockState blockState, FluidState fluidState, BlockPos blockPos, BlockPos offset,
								   TranslucentGeometryCollector collector, ChunkModelBuilder meshBuilder, Material material,
								   ColorProvider<FluidState> colorProvider, FluidModel sprites, CallbackInfo ci) {
		coffeeclient$alpha = XRay.fluidAlpha(fluidState, blockPos, level);
	}

	@ModifyReturnValue(method = "isFluidSideExposed(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;F)Z",
		at = @At("RETURN"))
	private boolean coffeeclient$xraySide(boolean original, BlockState own, BlockState neighbor, Direction facing, float height) {
		if (original || !XRay.active() || facing.getAxis().isVertical()) return original;
		if (!XRay.blocked(neighbor.getBlock(), null, null)) return false;
		return !neighbor.getFluidState().getType().isSame(own.getFluidState().getType());
	}

	@Inject(method = "updateQuad", at = @At("TAIL"))
	private void coffeeclient$xrayAlpha(ModelQuadViewMutable quad, LevelSlice level, BlockPos pos, LightPipeline lighter, Direction dir,
										ModelQuadFacing facing, float brightness, ColorProvider<FluidState> colorProvider, FluidState fluidState, CallbackInfo ci) {
		if (coffeeclient$alpha == -1) return;
		for (int i = 0; i < 4; i++) quadColors[i] = (quadColors[i] & 0xFFFFFF) | (coffeeclient$alpha << 24);
	}
}
