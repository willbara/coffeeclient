package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.movement.TridentBoost;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** TridentBoost: scale the riptide push and allow it out of water. */
@Mixin(TridentItem.class)
abstract class TridentItemMixin {
	@ModifyArgs(method = "releaseUsing", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;push(DDD)V"))
	private void coffeeclient$modifyRiptide(Args args, ItemStack stack, Level level, LivingEntity entity, int remainingTime) {
		if (entity != net.minecraft.client.Minecraft.getInstance().player) return;
		double multiplier = ModuleManager.on(TridentBoost.class) ? CoffeeClient.modules().get(TridentBoost.class).getMultiplier() : 1;
		args.set(0, (double) args.get(0) * multiplier);
		args.set(1, (double) args.get(1) * multiplier);
		args.set(2, (double) args.get(2) * multiplier);
	}

	@ModifyExpressionValue(method = {"use", "releaseUsing"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;isInWaterOrRain()Z"))
	private boolean coffeeclient$allowOutOfWater(boolean original) {
		return ModuleManager.on(TridentBoost.class) ? CoffeeClient.modules().get(TridentBoost.class).allowOutOfWater() || original : original;
	}
}
