package dev.coffeeclient.mixin;

import dev.coffeeclient.module.modules.render.Ambience;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ambience: client-side rain/thunder levels (server levels are untouched). */
@Mixin(Level.class)
abstract class LevelMixin {
	@Inject(method = "getRainLevel", at = @At("RETURN"), cancellable = true)
	private void coffeeclient$rain(float a, CallbackInfoReturnable<Float> cir) {
		if ((Object) this instanceof ClientLevel) cir.setReturnValue(Ambience.rain(cir.getReturnValueF()));
	}

	@Inject(method = "getThunderLevel", at = @At("RETURN"), cancellable = true)
	private void coffeeclient$thunder(float a, CallbackInfoReturnable<Float> cir) {
		if ((Object) this instanceof ClientLevel) cir.setReturnValue(Ambience.thunder(cir.getReturnValueF()));
	}
}
