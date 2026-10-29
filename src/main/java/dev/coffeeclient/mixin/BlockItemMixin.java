package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.coffeeclient.module.modules.exploit.GhostBlockCheck;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** GhostBlockCheck's confirm only: a placement goes to the server, but the block appears only when it agrees. */
@Mixin(BlockItem.class)
abstract class BlockItemMixin {
	@WrapOperation(method = "placeBlock", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
	private boolean coffeeclient$confirmPlace(Level level, BlockPos pos, BlockState state, int flags, Operation<Boolean> original) {
		if (level.isClientSide() && GhostBlockCheck.confirmOnlyOn()) return true;
		return original.call(level, pos, state, flags);
	}
}
