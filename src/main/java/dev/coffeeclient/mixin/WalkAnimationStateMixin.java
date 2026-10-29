package dev.coffeeclient.mixin;

import dev.coffeeclient.util.fakeplayer.FrozenWalkCycle;
import net.minecraft.world.entity.WalkAnimationState;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets a frozen fake player hold its walk cycle. Off by default, so real players are untouched. */
@Mixin(WalkAnimationState.class)
@Implements(@Interface(iface = FrozenWalkCycle.class, prefix = "coffeeclient$", unique = true))
abstract class WalkAnimationStateMixin {
	@Unique
	private boolean coffeeclient$frozen;
	@Unique
	private float coffeeclient$frozenSpeed;
	@Unique
	private float coffeeclient$frozenPosition;

	public void coffeeclient$freeze(float speed, float position) {
		coffeeclient$frozen = true;
		coffeeclient$frozenSpeed = speed;
		coffeeclient$frozenPosition = position;
	}

	public void coffeeclient$thaw() {
		coffeeclient$frozen = false;
	}

	// Hooks must not use the interface prefix, or Mixin looks for interface methods with these names and fails.
	@Inject(method = "speed()F", at = @At("HEAD"), cancellable = true)
	private void onSpeed(CallbackInfoReturnable<Float> cir) {
		if (coffeeclient$frozen) cir.setReturnValue(coffeeclient$frozenSpeed);
	}

	@Inject(method = "speed(F)F", at = @At("HEAD"), cancellable = true)
	private void onSpeedPartial(float partial, CallbackInfoReturnable<Float> cir) {
		if (coffeeclient$frozen) cir.setReturnValue(coffeeclient$frozenSpeed);
	}

	@Inject(method = "position()F", at = @At("HEAD"), cancellable = true)
	private void onPosition(CallbackInfoReturnable<Float> cir) {
		if (coffeeclient$frozen) cir.setReturnValue(coffeeclient$frozenPosition);
	}

	@Inject(method = "position(F)F", at = @At("HEAD"), cancellable = true)
	private void onPositionPartial(float partial, CallbackInfoReturnable<Float> cir) {
		if (coffeeclient$frozen) cir.setReturnValue(coffeeclient$frozenPosition);
	}
}
