package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.Events;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.movement.EntityFly;
import dev.coffeeclient.module.modules.movement.NoSlow;
import dev.coffeeclient.module.modules.movement.Sprint;
import dev.coffeeclient.module.modules.movement.Velocity;
import dev.coffeeclient.module.modules.player.LiquidInteract;
import dev.coffeeclient.module.modules.player.NoMiningTrace;
import dev.coffeeclient.module.modules.player.InventoryPlus;
import dev.coffeeclient.module.modules.render.Freecam;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** NoSlow (items / sneaking / air-strict), Sprint (hungry, blind and bump sprint), Velocity (block pushing) and the item-drop event on the local player. */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerMixin {
	/**
	 * Keys changed before sprinting and slowdowns read them: SnapTap resolves opposite keys,
	 * TargetStrafe walks a circle, SuperKnockback's W-tap lets go of forward, and movement correction
	 * turns them to the yaw the server is sent.
	 */
	@Inject(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/ClientInput;tick()V", shift = At.Shift.AFTER))
	private void coffeeclient$correctInput(CallbackInfo ci) {
		LocalPlayer self = (LocalPlayer) (Object) this;
		ClientInputAccessor input = (ClientInputAccessor) self.input;
		net.minecraft.world.phys.Vec2 move = dev.coffeeclient.module.modules.movement.SnapTap.resolve(self.input.getMoveVector());
		float strafe = dev.coffeeclient.module.modules.combat.TargetStrafe.heading();
		// Walking forward along the strafe heading, in keys relative to where you face.
		if (!Float.isNaN(strafe)) move = dev.coffeeclient.util.Rotations.correctInput(new net.minecraft.world.phys.Vec2(0, 1), strafe, self.getYRot());
		if (dev.coffeeclient.module.modules.combat.SuperKnockback.releaseForward() && move.y > 0) move = new net.minecraft.world.phys.Vec2(move.x, 0).normalized();
		input.coffeeclient$setMoveVector(move);
		float sent = dev.coffeeclient.util.Rotations.movementYaw();
		if (!Float.isNaN(sent)) input.coffeeclient$setMoveVector(dev.coffeeclient.util.Rotations.correctInput(move, self.getYRot(), sent));
		Input keys = self.input.keyPresses;
		if (dev.coffeeclient.util.Player.takeJump() && !keys.jump()) {
			self.input.keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), true, keys.shift(), keys.sprint());
		}
	}

	/** KillAura crits: no sprint while it waits in the air for a falling hit (sprinting hits never crit). */
	@ModifyReturnValue(method = "isSprintingPossible", at = @At("RETURN"))
	private boolean coffeeclient$critSprintHold(boolean original) {
		return original && !dev.coffeeclient.module.modules.combat.KillAura.critSprintHold();
	}

	/** Sprint's hungry sprint: food no longer stops you sprinting. */
	@ModifyExpressionValue(method = "isSprintingPossible", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/player/LocalPlayer;hasEnoughFoodToDoExhaustiveManoeuvres()Z"))
	private boolean coffeeclient$hungrySprint(boolean original) {
		return original || Sprint.hungry();
	}

	/** Sprint's ignore blindness. */
	@ModifyExpressionValue(method = "isSprintingPossible", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/player/LocalPlayer;isMobilityRestricted()Z"))
	private boolean coffeeclient$blindSprint(boolean original) {
		return original && !Sprint.blind();
	}

	/** Sprint's ignore collision: a wall no longer cancels the sprint. */
	@ModifyExpressionValue(method = "shouldStopRunSprinting", at = @At(value = "FIELD",
		target = "Lnet/minecraft/client/player/LocalPlayer;horizontalCollision:Z"))
	private boolean coffeeclient$bumpSprint(boolean original) {
		return original && !Sprint.bump();
	}

	/** EntityFly's max jump: any charged horse jump counts as full. */
	@ModifyReturnValue(method = "getJumpRidingScale", at = @At("RETURN"))
	private float coffeeclient$maxJump(float original) {
		return original > 0 && EntityFly.maxJump() ? 1 : original;
	}

	@ModifyExpressionValue(method = "modifyInput", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z"))
	private boolean coffeeclient$usingItem(boolean original) {
		return NoSlow.items() ? false : original;
	}

	@ModifyReturnValue(method = "modifyInput", at = @At("RETURN"))
	private net.minecraft.world.phys.Vec2 coffeeclient$noSlowShares(net.minecraft.world.phys.Vec2 original) {
		return NoSlow.scaleInput(original);
	}

	/** NoSlow "Allow swimming": using an item no longer blocks sprinting while in water. */
	@ModifyReturnValue(method = "isSlowDueToUsingItem", at = @At("RETURN"))
	private boolean coffeeclient$swimWhileUsing(boolean original) {
		LocalPlayer self = (LocalPlayer) (Object) this;
		return original && !(NoSlow.allowSwimming() && self.isInWater());
	}

	@Inject(method = "isMovingSlowly", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$movingSlowly(CallbackInfoReturnable<Boolean> cir) {
		LocalPlayer self = (LocalPlayer) (Object) this;
		if (NoSlow.sneaking()) cir.setReturnValue(self.isVisuallyCrawling());
	}

	@Inject(method = "moveTowardsClosestSpace", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$moveTowardsClosestSpace(double x, double z, CallbackInfo ci) {
		if (!ModuleManager.on(Velocity.class)) return;
		if (CoffeeClient.modules().get(Velocity.class).blocks.get()) ci.cancel();
	}

	@Inject(method = "drop", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$dropItem(boolean all, CallbackInfoReturnable<Boolean> cir) {
		if (CoffeeClient.events() == null) return;
		LocalPlayer self = (LocalPlayer) (Object) this;
		Events.DropItems event = CoffeeClient.events().post(new Events.DropItems(self.getInventory().getSelectedItem()));
		if (event.isCancelled()) cir.setReturnValue(false);
	}

	@Inject(method = "sendPosition", at = @At("HEAD"))
	private void coffeeclient$preMovement(CallbackInfo ci) {
		if (CoffeeClient.events() != null) CoffeeClient.events().post(Events.SendMovementPackets.Pre.INSTANCE);
	}

	@Inject(method = "sendPosition", at = @At("RETURN"))
	private void coffeeclient$postMovement(CallbackInfo ci) {
		if (CoffeeClient.events() != null) CoffeeClient.events().post(Events.SendMovementPackets.Post.INSTANCE);
	}

	@ModifyExpressionValue(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/client/player/ClientInput;keyPresses:Lnet/minecraft/world/entity/player/Input;"))
	private Input coffeeclient$airStrict(Input original) {
		boolean freecamSneak = ModuleManager.on(Freecam.class) && CoffeeClient.modules().get(Freecam.class).shouldStaySneaking();
		if (!NoSlow.airStrict() && !freecamSneak) return original;
		return new Input(original.forward(), original.backward(), original.left(), original.right(), original.jump(), original.shift() || NoSlow.airStrict() || freecamSneak, original.sprint());
	}

	// Inventory+ portal GUIs: skip the portal transition screen blur.
	@ModifyExpressionValue(method = "handlePortalTransitionEffect", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;screen()Lnet/minecraft/client/gui/screens/Screen;", ordinal = 0))
	private Screen coffeeclient$portalTransition(Screen original) {
		if (ModuleManager.on(InventoryPlus.class) && CoffeeClient.modules().get(InventoryPlus.class).portalGuis.get()) return null;
		return original;
	}

	// LiquidInteract: raycast against liquids when the normal pick misses.
	@ModifyExpressionValue(method = "pick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;pick(DFZ)Lnet/minecraft/world/phys/HitResult;"))
	private static HitResult coffeeclient$liquidInteract(HitResult original, final Entity cameraEntity, final double blockInteractionRange, final double entityInteractionRange, final float partialTicks, @com.llamalad7.mixinextras.sugar.Local(name = "maxDistance") double maxDistance) {
		if (!ModuleManager.on(LiquidInteract.class)) return original;
		if (original.getType() != HitResult.Type.MISS) return original;
		return cameraEntity.pick(maxDistance, partialTicks, true);
	}

	// NoMiningTrace: mine through entities.
	@ModifyReturnValue(method = "pick", at = @At("RETURN"))
	private static HitResult coffeeclient$noMiningTrace(HitResult original, @com.llamalad7.mixinextras.sugar.Local(name = "blockHitResult") HitResult blockHitResult) {
		if (original instanceof EntityHitResult ehr) {
			if (ModuleManager.on(NoMiningTrace.class)) {
				NoMiningTrace m = (NoMiningTrace) CoffeeClient.modules().get(NoMiningTrace.class);
				if (m.canWork(ehr.getEntity()) && blockHitResult.getType() == HitResult.Type.BLOCK) {
					return blockHitResult;
				}
			}
		}
		return original;
	}
}
