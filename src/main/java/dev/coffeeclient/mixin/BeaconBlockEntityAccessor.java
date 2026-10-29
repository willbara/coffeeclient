package dev.coffeeclient.mixin;

import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** BlockESP's beacon range: the pyramid level the client works out every 80 ticks. */
@Mixin(BeaconBlockEntity.class)
public interface BeaconBlockEntityAccessor {
	@Accessor("levels")
	int coffeeclient$levels();
}
