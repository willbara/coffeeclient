package dev.coffeeclient.mixin;

import dev.coffeeclient.module.modules.movement.NoSlow;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NoSlow: berry bushes stop slowing you down. */
@Mixin(SweetBerryBushBlock.class)
abstract class SweetBerryBushBlockMixin {
	@Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$entityInside(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effectApplier, boolean precise, CallbackInfo ci) {
		if (entity == net.minecraft.client.Minecraft.getInstance().player && NoSlow.berryBush()) ci.cancel();
	}
}
