package dev.coffeeclient.mixin;

import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.movement.ElytraFly;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** ElytraFly's Rocket boost: scales the velocity a rocket adds to the local player each tick. */
@Mixin(FireworkRocketEntity.class)
abstract class FireworkRocketEntityMixin {
	@Shadow
	private LivingEntity attachedToEntity;

	@ModifyArgs(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;add(DDD)Lnet/minecraft/world/phys/Vec3;"))
	private void coffeeclient$rocketBoost(Args args) {
		LivingEntity user = attachedToEntity;
		if (user == null || user != Minecraft.getInstance().player || !user.isFallFlying()) return;
		ElytraFly elytraFly = ModuleManager.ifOn(ElytraFly.class);
		if (elytraFly == null) return;
		Vec3 push = elytraFly.fireworks.boostPush(user.getDeltaMovement(), new Vec3(args.get(0), args.get(1), args.get(2)));
		args.set(0, push.x);
		args.set(1, push.y);
		args.set(2, push.z);
	}
}
