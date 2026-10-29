package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.movement.Velocity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityFluidInteraction;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Velocity: scale down the push from flowing liquids. */
@Mixin(EntityFluidInteraction.class)
abstract class EntityFluidInteractionMixin {
	@ModifyExpressionValue(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FluidState;getFlow(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 coffeeclient$fluidFlow(Vec3 flow, Entity entity, boolean ignoreCurrent) {
		if (entity != net.minecraft.client.Minecraft.getInstance().player) return flow;
		if (!ModuleManager.on(Velocity.class)) return flow;
		Velocity velocity = CoffeeClient.modules().get(Velocity.class);
		if (!velocity.liquids.get()) return flow;
		double h = velocity.liquidsHorizontal.get();
		double v = velocity.liquidsVertical.get();
		return flow.multiply(h, v, h);
	}
}
