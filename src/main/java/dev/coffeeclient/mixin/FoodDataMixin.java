package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.coffeeclient.module.modules.movement.NoSlow;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** NoSlow: hunger no longer slows you down. */
@Mixin(FoodData.class)
abstract class FoodDataMixin {
	@ModifyExpressionValue(method = "hasEnoughFood()Z", at = @At(value = "CONSTANT", args = "floatValue=6.0f"))
	private float coffeeclient$hunger(float constant) {
		return NoSlow.hunger() ? -1 : constant;
	}
}
