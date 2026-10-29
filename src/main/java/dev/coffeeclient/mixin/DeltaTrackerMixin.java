package dev.coffeeclient.mixin;

import dev.coffeeclient.module.modules.movement.Timer;
import net.minecraft.client.DeltaTracker;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Timer: scales the client's tick delta so the whole game runs faster or slower. */
@Mixin(DeltaTracker.Timer.class)
abstract class DeltaTrackerMixin {
	@Shadow
	private float deltaTicks;

	@Inject(method = "advanceGameTime(J)I", at = @At(value = "FIELD", target = "Lnet/minecraft/client/DeltaTracker$Timer;lastMs:J", opcode = Opcodes.PUTFIELD))
	private void coffeeclient$timer(long currentMs, CallbackInfoReturnable<Integer> cir) {
		deltaTicks *= (float) Timer.multiplier();
	}
}
