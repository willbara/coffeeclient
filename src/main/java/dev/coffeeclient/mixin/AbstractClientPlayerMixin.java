package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.coffeeclient.module.modules.render.NoEffect;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** NoEffect's FOV options: the movement part of the FOV modifier, without effects or not at all. */
@Mixin(AbstractClientPlayer.class)
abstract class AbstractClientPlayerMixin {
	@ModifyExpressionValue(method = "getFieldOfViewModifier", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/player/AbstractClientPlayer;getAttributeValue(Lnet/minecraft/core/Holder;)D"))
	private double coffeeclient$fovSpeed(double original) {
		AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
		return switch (NoEffect.fovMode()) {
			case VANILLA -> original;
			case NO_EFFECTS -> NoEffect.speedWithoutEffects(self, original);
			// Equal to the walking speed, so the speed factor is 1.
			case STEADY -> self.getAbilities().getWalkingSpeed();
		};
	}

	@ModifyExpressionValue(method = "getFieldOfViewModifier", at = @At(value = "FIELD",
		target = "Lnet/minecraft/world/entity/player/Abilities;flying:Z"))
	private boolean coffeeclient$fovFlying(boolean original) {
		return original && NoEffect.fovMode() != NoEffect.Fov.STEADY;
	}
}
