package dev.coffeeclient.mixin;

import dev.coffeeclient.module.modules.movement.NoSlow;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NoSlow: slime blocks do not bounce you. */
@Mixin(SlimeBlock.class)
abstract class SlimeBlockMixin {
	@Inject(method = "stepOn", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$stepOn(Level level, BlockPos pos, BlockState onState, Entity entity, CallbackInfo ci) {
		if (entity == net.minecraft.client.Minecraft.getInstance().player && NoSlow.slimeBlock()) ci.cancel();
	}
}
