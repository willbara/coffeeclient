package dev.coffeeclient.mixin;

import dev.coffeeclient.module.modules.misc.SoundBlocker;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** SoundBlocker: stop selected sounds from ever playing. */
@Mixin(SoundEngine.class)
abstract class SoundEngineMixin {
	@Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$play(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
		if (SoundBlocker.shouldBlock(instance)) cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
	}
}
