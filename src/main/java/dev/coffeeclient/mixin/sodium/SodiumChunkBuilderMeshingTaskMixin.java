package dev.coffeeclient.mixin.sodium;

import dev.coffeeclient.module.modules.render.XRay;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** XRay: sort see-through terrain once per mesh instead of every camera move (whole chunks turn translucent). */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
abstract class SodiumChunkBuilderMeshingTaskMixin {
	@ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true)
	private static SortBehavior coffeeclient$xraySort(SortBehavior sortBehavior) {
		return XRay.active() ? SortBehavior.STATIC : sortBehavior;
	}
}
