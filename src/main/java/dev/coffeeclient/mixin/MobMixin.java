package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.coffeeclient.module.modules.movement.EntityFly;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** EntityFly's Ride without saddle: the mount you ride counts as saddled. */
@Mixin(Mob.class)
abstract class MobMixin {
	@ModifyReturnValue(method = "isSaddled", at = @At("RETURN"))
	private boolean coffeeclient$unsaddled(boolean original) {
		return original || EntityFly.ridesUnsaddled(((Mob) (Object) this).getFirstPassenger());
	}
}
