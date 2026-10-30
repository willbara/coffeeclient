package dev.coffeeclient.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the last input the server was told about, so ElytraFly can word a start-fly the way a vanilla client would. */
@Mixin(LocalPlayer.class)
public interface LocalPlayerAccessor {
	@Accessor("lastSentInput")
	Input coffeeclient$lastSentInput();
}
