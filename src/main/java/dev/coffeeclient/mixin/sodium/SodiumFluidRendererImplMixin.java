package dev.coffeeclient.mixin.sodium;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.coffeeclient.module.modules.render.XRay;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.caffeinemc.mods.sodium.fabric.render.FluidRendererImpl;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** XRay on Sodium's fluid path: skip hidden fluids, send see-through ones to the translucent pass. */
@Mixin(value = FluidRendererImpl.class, remap = false)
abstract class SodiumFluidRendererImplMixin {
	@Unique
	private int coffeeclient$alpha = -1;

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$xray(LevelSlice level, BlockState blockState, FluidState fluidState, BlockPos blockPos, BlockPos offset,
								   TranslucentGeometryCollector collector, ChunkBuildBuffers buffers, CallbackInfo ci) {
		coffeeclient$alpha = XRay.fluidAlpha(fluidState, blockPos, level);
		if (coffeeclient$alpha == 0) ci.cancel();
	}

	@ModifyExpressionValue(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/DefaultMaterials;forChunkLayer(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/Material;"))
	private Material coffeeclient$xrayMaterial(Material material) {
		return coffeeclient$alpha != -1 ? DefaultMaterials.TRANSLUCENT : material;
	}
}
