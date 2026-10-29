package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.render.Freecam;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Freecam: detach and steer the camera while the module is active. CameraTweaks: distance, clipping and zoom. */
@Mixin(Camera.class)
abstract class CameraMixin {
	@Shadow
	private boolean detached;

	@ModifyArgs(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V"))
	private void coffeeclient$freecamPosition(Args args, @Local(argsOnly = true) float partialTick) {
		if (!ModuleManager.on(Freecam.class)) return;
		Freecam freecam = CoffeeClient.modules().get(Freecam.class);
		if (!freecam.isEnabled()) return;
		args.set(0, freecam.x(partialTick));
		args.set(1, freecam.y(partialTick));
		args.set(2, freecam.z(partialTick));
	}

	@ModifyArgs(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
	private void coffeeclient$freecamRotation(Args args, @Local(argsOnly = true) float partialTick) {
		if (!ModuleManager.on(Freecam.class)) return;
		Freecam freecam = CoffeeClient.modules().get(Freecam.class);
		if (!freecam.isEnabled()) return;
		args.set(0, freecam.yaw(partialTick));
		args.set(1, freecam.pitch(partialTick));
	}

	/** FreeLook: the camera keeps the angles the mouse gave it while the player keeps facing where they were. */
	@ModifyArgs(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
	private void coffeeclient$freeLookRotation(Args args) {
		if (!dev.coffeeclient.module.modules.render.CameraTweaks.freeLooking() || ModuleManager.on(Freecam.class)) return;
		args.set(0, dev.coffeeclient.module.modules.render.CameraTweaks.lookYaw());
		args.set(1, dev.coffeeclient.module.modules.render.CameraTweaks.lookPitch());
	}

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void coffeeclient$freecamDetached(float partialTick, CallbackInfo ci) {
		if (ModuleManager.on(Freecam.class)) {
			Freecam freecam = CoffeeClient.modules().get(Freecam.class);
			if (freecam.isEnabled()) detached = true;
		}
	}

	@ModifyVariable(method = "getMaxZoom", at = @At("HEAD"), argsOnly = true)
	private float coffeeclient$cameraDistance(float cameraDist) {
		if (ModuleManager.on(Freecam.class)) {
			Freecam freecam = CoffeeClient.modules().get(Freecam.class);
			if (freecam.isEnabled()) return 0f;
		}
		if (ModuleManager.on(dev.coffeeclient.module.modules.render.CameraTweaks.class)) {
			return (float) CoffeeClient.modules().get(dev.coffeeclient.module.modules.render.CameraTweaks.class).distance;
		}
		return cameraDist;
	}

	@Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$cameraClip(float cameraDist, CallbackInfoReturnable<Float> cir) {
		if (ModuleManager.on(dev.coffeeclient.module.modules.render.CameraTweaks.class)) {
			var tweaks = CoffeeClient.modules().get(dev.coffeeclient.module.modules.render.CameraTweaks.class);
			if (tweaks.clip.get()) cir.setReturnValue(cameraDist);
		}
	}

	@ModifyReturnValue(method = "calculateFov(F)F", at = @At("RETURN"))
	private float coffeeclient$zoomFov(float fov) {
		return (float) (fov / dev.coffeeclient.module.modules.render.CameraTweaks.zoomFactor());
	}

	/** The hand view zooms too, so the held item moves out of the way. */
	@ModifyReturnValue(method = "calculateHudFov(F)F", at = @At("RETURN"))
	private float coffeeclient$zoomHudFov(float fov) {
		return (float) (fov / dev.coffeeclient.module.modules.render.CameraTweaks.zoomFactor());
	}
}
