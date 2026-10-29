package dev.coffeeclient.mixin;

import dev.coffeeclient.module.modules.client.StreamerMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** StreamerMode: the random offset of flowers and grass, which would also give the position away. */
@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockStateBaseMixin {
	@Inject(method = "getOffset", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$noOffset(BlockPos pos, CallbackInfoReturnable<Vec3> cir) {
		if (StreamerMode.noOffset((BlockState) (Object) this)) cir.setReturnValue(Vec3.ZERO);
	}

	@ModifyVariable(method = "getOffset", at = @At("HEAD"), argsOnly = true)
	private BlockPos coffeeclient$shuffledOffset(BlockPos pos) {
		return StreamerMode.offsetPos((BlockState) (Object) this, pos);
	}
}
