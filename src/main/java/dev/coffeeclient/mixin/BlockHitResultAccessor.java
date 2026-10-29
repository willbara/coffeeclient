package dev.coffeeclient.mixin;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets BuildHeight rewrite the face of an outgoing block interaction. */
@Mixin(BlockHitResult.class)
public interface BlockHitResultAccessor {
	@Mutable
	@Accessor("direction")
	void coffeeclient$setDirection(Direction direction);
}
