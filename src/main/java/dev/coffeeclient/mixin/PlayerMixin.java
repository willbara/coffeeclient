package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.movement.NoSlow;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.modules.movement.NoSlowBreak;
import dev.coffeeclient.module.modules.player.Reach;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reach, the clip-at-ledge event (SafeWalk) and NoSlow's slowness hook. */
@Mixin(Player.class)
abstract class PlayerMixin {
	@ModifyReturnValue(method = "blockInteractionRange", at = @At("RETURN"))
	private double coffeeclient$blockReach(double original) {
		if ((Object) this != Minecraft.getInstance().player || !ModuleManager.on(Reach.class)) return original;
		return original + CoffeeClient.modules().get(Reach.class).blockReach();
	}

	@ModifyReturnValue(method = "entityInteractionRange", at = @At("RETURN"))
	private double coffeeclient$entityReach(double original) {
		if ((Object) this != Minecraft.getInstance().player || !ModuleManager.on(Reach.class)) return original;
		return original + CoffeeClient.modules().get(Reach.class).entityReach();
	}

	@Inject(method = "isStayingOnGroundSurface", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$clipAtLedge(CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this != Minecraft.getInstance().player) return;
		var bus = CoffeeClient.events();
		if (bus == null || !bus.has(Events.ClipAtLedge.class)) return;
		if (bus.post(new Events.ClipAtLedge()).clip) cir.setReturnValue(true);
	}

	/** NoSlowBreak: Mining Fatigue does not slow your mining. */
	@ModifyExpressionValue(method = "getDestroySpeed", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/player/Player;hasEffect(Lnet/minecraft/core/Holder;)Z"))
	private boolean coffeeclient$noFatigue(boolean original) {
		return original && !((Object) this == Minecraft.getInstance().player && NoSlowBreak.miningFatigue());
	}

	/** NoSlowBreak: being under water does not slow your mining. */
	@ModifyExpressionValue(method = "getDestroySpeed", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/player/Player;isEyeInFluid(Lnet/minecraft/tags/TagKey;)Z"))
	private boolean coffeeclient$noWaterSlow(boolean original) {
		return original && !((Object) this == Minecraft.getInstance().player && NoSlowBreak.underwater());
	}

	/** NoSlowBreak: mining in the air counts as on the ground. */
	@ModifyExpressionValue(method = "getDestroySpeed", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/player/Player;onGround()Z"))
	private boolean coffeeclient$noAirSlow(boolean original) {
		return original || (Object) this == Minecraft.getInstance().player && NoSlowBreak.inAir();
	}

	@ModifyReturnValue(method = "getSpeed", at = @At("RETURN"))
	private float coffeeclient$getSpeed(float original) {
		if (!NoSlow.slowness()) return original;
		Player self = (Player) (Object) this;
		if (!self.level().isClientSide()) return original;

		float walkSpeed = self.getAbilities().getWalkingSpeed();
		if (original < walkSpeed) {
			return self.isSprinting() ? (float) (walkSpeed * 1.3) : walkSpeed;
		}
		return original;
	}
}
