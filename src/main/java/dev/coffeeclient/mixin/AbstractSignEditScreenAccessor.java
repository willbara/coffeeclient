package dev.coffeeclient.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the sign being edited, and which side, so AutoSign can queue its update packet. */
@Mixin(AbstractSignEditScreen.class)
public interface AbstractSignEditScreenAccessor {
	@Accessor("sign")
	SignBlockEntity coffeeclient$getSign();

	@Accessor("isFrontText")
	boolean coffeeclient$isFrontText();
}
