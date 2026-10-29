package dev.coffeeclient.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** AutoFish: the open-water test runs on the server only, so the client asks the hook directly. */
@Mixin(FishingHook.class)
public interface FishingHookAccessor {
	@Invoker("calculateOpenWater")
	boolean coffeeclient$calculateOpenWater(BlockPos pos);
}
