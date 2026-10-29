package dev.coffeeclient.mixin;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.modules.player.PotionSaver;
import dev.coffeeclient.util.ErrorGuard;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MobEffectInstance.class)
public abstract class MobEffectInstanceMixin {
	@Inject(method = "tickDownDuration", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$freeze(CallbackInfo ci) {
		if (!dev.coffeeclient.util.Player.canUpdate()) return;
		try {
			PotionSaver ps = CoffeeClient.modules().get(PotionSaver.class);
			if (ps != null && ps.shouldFreeze(((MobEffectInstance) (Object) this).getEffect().value())) ci.cancel();
		} catch (Throwable t) {
			ErrorGuard.report("potion-saver", t);
		}
	}
}
