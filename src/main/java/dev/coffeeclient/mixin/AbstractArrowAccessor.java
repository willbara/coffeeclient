package dev.coffeeclient.mixin;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Trajectories: arrows stuck in a block have no path left to predict. */
@Mixin(AbstractArrow.class)
public interface AbstractArrowAccessor {
	@Invoker("isInGround")
	boolean coffeeclient$isInGround();
}
