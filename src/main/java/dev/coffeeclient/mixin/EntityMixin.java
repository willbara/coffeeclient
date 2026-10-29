package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.movement.HighJump;
import dev.coffeeclient.module.modules.movement.Jesus;
import dev.coffeeclient.module.modules.movement.NoSlow;
import dev.coffeeclient.module.modules.movement.Velocity;
import dev.coffeeclient.module.modules.render.Outlines;
import dev.coffeeclient.module.modules.render.Freecam;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Movement, fluid and knockback hooks for the ported movement modules (HighJump, NoSlow, Velocity,
 * Jesus) plus the existing ESP outline colour.
 */
@Mixin(Entity.class)
abstract class EntityMixin {
	/** Hitbox: other entities' pick box grows. */
	@ModifyReturnValue(method = "getPickRadius", at = @At("RETURN"))
	private float coffeeclient$hitbox(float original) {
		return original + dev.coffeeclient.module.modules.combat.Hitbox.expand((Entity) (Object) this);
	}

	/** Movement correction: walk along the yaw the server is sent. */
	@ModifyArg(method = "moveRelative", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getInputVector(Lnet/minecraft/world/phys/Vec3;FF)Lnet/minecraft/world/phys/Vec3;"), index = 2)
	private float coffeeclient$movementYaw(float yaw) {
		if ((Object) this != Minecraft.getInstance().player) return yaw;
		float sent = dev.coffeeclient.util.Rotations.movementYaw();
		return Float.isNaN(sent) ? yaw : sent;
	}

	@Inject(method = "turn", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$freecamLook(double deltaX, double deltaY, CallbackInfo ci) {
		if ((Object) this != Minecraft.getInstance().player) return;
		Freecam freecam = ModuleManager.ifOn(Freecam.class);
		if (freecam == null) {
			if (dev.coffeeclient.module.modules.render.CameraTweaks.turnFreeLook(deltaX, deltaY)) ci.cancel();
			return;
		}
		freecam.changeLookDirection(deltaX, deltaY);
		ci.cancel();
	}

	@Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$outlineColor(CallbackInfoReturnable<Integer> cir) {
		Entity self = (Entity) (Object) this;
		if (!self.level().isClientSide() || self.isCurrentlyGlowing()) return;
		int color = Outlines.color(self);
		if (color >= 0) cir.setReturnValue(color);
	}

	/** NoSlow fluid drag: stop water/lava slowing the player. */
	@Inject(method = {"isInWater", "isInLava"}, at = @At("HEAD"), cancellable = true)
	private void coffeeclient$noFluidSlow(CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this != Minecraft.getInstance().player) return;
		if (NoSlow.fluidDrag()) cir.setReturnValue(false);
	}

	@ModifyExpressionValue(method = "updateSwimming", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;isUnderWater()Z"))
	private boolean coffeeclient$updateSwimming(boolean submerged) {
		if ((Object) this != Minecraft.getInstance().player) return submerged;
		return NoSlow.fluidDrag() ? false : submerged;
	}

	/** Jesus: remember that we are inside a bubble column this tick so it can lift us. */
	@Inject(method = {"onAboveBubbleColumn", "onInsideBubbleColumn"}, at = @At("HEAD"))
	private void coffeeclient$bubbleColumn(CallbackInfo ci) {
		if ((Object) this != Minecraft.getInstance().player) return;
		Jesus jesus = ModuleManager.ifOn(Jesus.class);
		if (jesus != null) jesus.isInBubbleColumn = true;
	}

	/** Velocity: scale down being pushed by other entities. */
	@ModifyArgs(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;push(DDD)V"))
	private void coffeeclient$entityPush(Args args) {
		if ((Object) this != Minecraft.getInstance().player) return;
		Velocity velocity = ModuleManager.ifOn(Velocity.class);
		if (velocity == null || !velocity.entityPush.get()) return;
		double multiplier = velocity.entityPushAmount.get();
		args.set(0, (double) args.get(0) * multiplier);
		args.set(2, (double) args.get(2) * multiplier);
	}

	/** HighJump: multiply the block jump factor. */
	@ModifyReturnValue(method = "getBlockJumpFactor", at = @At("RETURN"))
	private float coffeeclient$jumpFactor(float original) {
		if ((Object) this != Minecraft.getInstance().player) return original;
		HighJump highJump = ModuleManager.ifOn(HighJump.class);
		return highJump == null ? original : original * highJump.multiplier.get().floatValue();
	}

	/** Player move event: lets modules (Jesus, ElytraFly) steer the player's movement vector. */
	@ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true)
	private Vec3 coffeeclient$modifyMove(Vec3 delta, MoverType type) {
		if ((Object) this != Minecraft.getInstance().player) return delta;
		var bus = CoffeeClient.events();
		if (bus == null || !bus.has(Events.PlayerMove.class)) return delta;
		return bus.post(new Events.PlayerMove(type, delta)).movement;
	}

	/** NoSlow: treat soul sand and honey as normal blocks for movement speed. */
	@ModifyExpressionValue(method = "getBlockSpeedFactor", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getBlock()Lnet/minecraft/world/level/block/Block;"))
	private Block coffeeclient$speedFactor(Block original) {
		if ((Object) this != Minecraft.getInstance().player) return original;
		if (original == Blocks.SOUL_SAND && NoSlow.soulSand()) return Blocks.STONE;
		if (original == Blocks.HONEY_BLOCK && NoSlow.honeyBlock()) return Blocks.STONE;
		return original;
	}
}
