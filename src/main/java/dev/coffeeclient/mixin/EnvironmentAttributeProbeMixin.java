package dev.coffeeclient.mixin;

import dev.coffeeclient.module.modules.render.Ambience;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ambience: sky/fog/cloud colours, fog distance and cloud height (client thread only). */
@Mixin(EnvironmentAttributeProbe.class)
abstract class EnvironmentAttributeProbeMixin {
	@Inject(method = "getValue", at = @At("RETURN"), cancellable = true)
	private <V> void coffeeclient$override(EnvironmentAttribute<V> attribute, float partialTicks, CallbackInfoReturnable<V> cir) {
		V value = cir.getReturnValue();
		V changed = Ambience.attribute(attribute, value);
		if (changed != value) cir.setReturnValue(changed);
	}
}
