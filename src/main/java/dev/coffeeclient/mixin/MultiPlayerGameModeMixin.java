package dev.coffeeclient.mixin;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.player.BreakDelay;
import dev.coffeeclient.module.modules.player.SpeedMine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import dev.coffeeclient.core.Events;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** BreakDelay, SpeedMine, the block-interaction event and the attack event. */
@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeMixin {
	/** AutoTotem's server-side totem: no slot resync on the tick, only (as vanilla does) before an action. */
	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;ensureHasSentCarriedItem()V"))
	private void coffeeclient$keepServerTotem(MultiPlayerGameMode self, Operation<Void> original) {
		if (!dev.coffeeclient.module.modules.combat.AutoTotem.holdingServerTotem()) original.call(self);
	}

	/** Every attack the client makes, by hand or by a module (Killaura), before its packet goes out. */
	@Inject(method = "attack", at = @At("HEAD"))
	private void coffeeclient$attack(Player player, Entity target, CallbackInfo ci) {
		var bus = CoffeeClient.events();
		if (bus != null && player == Minecraft.getInstance().player) bus.post(new Events.AttackEntity(target));
	}

	/** GhostBlockCheck's confirm only: a break leaves the block until the server's update removes it. */
	@com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "destroyBlock", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
	private boolean coffeeclient$confirmBreak(net.minecraft.world.level.Level level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state, int flags,
											  com.llamalad7.mixinextras.injector.wrapoperation.Operation<Boolean> original) {
		return !dev.coffeeclient.module.modules.exploit.GhostBlockCheck.confirmOnlyOn() && original.call(level, pos, state, flags);
	}

	/** PacketMine: the player's own clicks become a packet break instead of vanilla's. */
	@Inject(method = {"startDestroyBlock", "continueDestroyBlock"}, at = @At("HEAD"), cancellable = true)
	private void coffeeclient$packetMine(BlockPos pos, Direction dir, CallbackInfoReturnable<Boolean> cir) {
		if (dev.coffeeclient.module.modules.player.PacketMine.takeOver(pos, dir)) cir.setReturnValue(true);
	}

	/** SpeedMine: a click on a block one tick breaks past Finish at breaks it at once. */
	@Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$instamine(BlockPos pos, Direction dir, CallbackInfoReturnable<Boolean> cir) {
		if (SpeedMine.instamine(pos, dir)) cir.setReturnValue(true);
	}

	/** BreakDelay: every 5-tick break cooldown (creative, and survival after a break). */
	@ModifyExpressionValue(method = {"startDestroyBlock", "continueDestroyBlock"}, at = @At(value = "CONSTANT", args = "intValue=5"))
	private int coffeeclient$breakDelay(int original) {
		return ModuleManager.on(BreakDelay.class) ? CoffeeClient.modules().get(BreakDelay.class).cooldown() : original;
	}

	/** BreakDelay's No insta break skips an instant break and waits the cooldown; SpeedMine scales the rest. */
	@ModifyExpressionValue(method = "continueDestroyBlock", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/block/state/BlockState;getDestroyProgress(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F"))
	private float coffeeclient$destroyProgress(float original) {
		BreakDelay breakDelay = ModuleManager.ifOn(BreakDelay.class);
		if (breakDelay != null && breakDelay.noInstaBreak.get() && original >= 1) {
			((MultiPlayerGameModeAccessor) this).coffeeclient$setDestroyDelay(breakDelay.cooldown());
			return 0;
		}
		return SpeedMine.scale(original);
	}

	@Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$interactBlock(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		var bus = CoffeeClient.events();
		if (bus == null || !bus.has(Events.InteractBlock.class)) return;
		if (bus.post(new Events.InteractBlock(hand, hit)).isCancelled()) cir.setReturnValue(InteractionResult.FAIL);
	}
}
