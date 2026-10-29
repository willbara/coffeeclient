package dev.coffeeclient.mixin;

import dev.coffeeclient.module.modules.client.StreamerMode;
import dev.coffeeclient.module.modules.render.XRay;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * XRay: no ambient-occlusion shading, so see-through stone does not darken the ores next to it.
 * StreamerMode: shuffled texture rotations (every block's model seed ends up here).
 */
@Mixin(BlockBehaviour.class)
abstract class BlockBehaviourMixin {
	@Inject(method = "getShadeBrightness", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$xrayShade(BlockState state, BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
		if (XRay.active()) cir.setReturnValue(1.0F);
	}

	@Inject(method = "getSeed", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$textureSeed(BlockState state, BlockPos pos, CallbackInfoReturnable<Long> cir) {
		Long seed = StreamerMode.textureSeed(state, pos);
		if (seed != null) cir.setReturnValue(seed);
	}
}
