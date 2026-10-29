package dev.coffeeclient.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Macros: queue a click on a key mapping (attack, use, drop, ...) as if its key was pressed once. */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
	@Accessor("clickCount")
	int coffeeclient$clickCount();

	@Accessor("clickCount")
	void coffeeclient$setClickCount(int count);
}
