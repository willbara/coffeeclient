package dev.coffeeclient.mixin;

import dev.coffeeclient.module.modules.render.Ambience;
import net.minecraft.client.ClientClockManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ambience: fixed time of day on the client's clocks (visual only). */
@Mixin(ClientClockManager.class)
abstract class ClientClockManagerMixin {
	@Inject(method = "getTotalTicks", at = @At("RETURN"), cancellable = true)
	private void coffeeclient$fixedTime(CallbackInfoReturnable<Long> cir) {
		cir.setReturnValue(Ambience.clock(cir.getReturnValueJ()));
	}
}
