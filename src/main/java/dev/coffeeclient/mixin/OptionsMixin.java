package dev.coffeeclient.mixin;

import dev.coffeeclient.module.modules.render.Ambience;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ambience: report clouds as off without touching the saved option. */
@Mixin(Options.class)
abstract class OptionsMixin {
	@Inject(method = "getCloudStatus", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$clouds(CallbackInfoReturnable<CloudStatus> cir) {
		if (Ambience.cloudsHidden()) cir.setReturnValue(CloudStatus.OFF);
	}
}
