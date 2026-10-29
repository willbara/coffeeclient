package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.movement.Jesus;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.PowderSnowBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Jesus: walk on powder snow. */
@Mixin(PowderSnowBlock.class)
abstract class PowderSnowBlockMixin {
	@ModifyReturnValue(method = "canEntityWalkOnPowderSnow", at = @At("RETURN"))
	private static boolean coffeeclient$walkOnPowderSnow(boolean original, Entity entity) {
		if (entity == net.minecraft.client.Minecraft.getInstance().player && ModuleManager.on(Jesus.class)) {
			return CoffeeClient.modules().get(Jesus.class).canWalkOnPowderSnow();
		}
		return original;
	}
}
