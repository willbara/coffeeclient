package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.coffeeclient.module.modules.movement.NoSlow;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Inject;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.injection.At;

/** NoSlow: slime blocks stop slowing you. */
@Mixin(Block.class)
abstract class BlockMixin {
	@ModifyReturnValue(method = "getFriction", at = @At("RETURN"))
	private float coffeeclient$friction(float original) {
		Block self = (Block) (Object) this;

		return self == Blocks.SLIME_BLOCK && NoSlow.slimeBlock() ? 0.6F : original;
	}

	/** XRay: listed blocks always show their faces. Runs before other mods' culling (More Culling). */
	@Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
	private static void coffeeclient$xrayFace(BlockState state, BlockState neighborState, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (dev.coffeeclient.module.modules.render.XRay.active() && !dev.coffeeclient.module.modules.render.XRay.blocked(state.getBlock(), null, null)) {
			cir.setReturnValue(true);
		}
	}
}
