package dev.coffeeclient.mixin.sodium;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.coffeeclient.module.modules.render.XRay;
import net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** XRay face culling on Sodium's mesher. Chunk-build threads. */
@Mixin(value = AbstractBlockRenderContext.class, remap = false)
abstract class SodiumBlockRenderContextMixin {
	@Shadow
	protected BlockState state;

	@Shadow
	protected BlockAndTintGetter level;

	@Shadow
	protected BlockPos pos;

	/** Listed blocks always draw their faces; runs before other mods' culling (More Culling). */
	@Inject(method = "shouldDrawSide", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$xrayForce(Direction facing, CallbackInfoReturnable<Boolean> cir) {
		if (XRay.active() && !XRay.blocked(state.getBlock(), null, null)) cir.setReturnValue(true);
	}

	@ModifyReturnValue(method = "shouldDrawSide", at = @At("RETURN"))
	private boolean coffeeclient$xrayFace(boolean original, Direction facing) {
		return XRay.active() ? XRay.drawSide(state, level, pos, facing, original) : original;
	}
}
