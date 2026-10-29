package dev.coffeeclient.mixin;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.player.BreakDelay;
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
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** BreakDelay, the block-interaction event and the attack event. */
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

	@Inject(method = "startDestroyBlock", at = @At("RETURN"))
	private void coffeeclient$breakDelay(BlockPos pos, Direction dir, CallbackInfoReturnable<Boolean> cir) {
		if (!ModuleManager.on(BreakDelay.class)) return;
		int cooldown = CoffeeClient.modules().get(BreakDelay.class).cooldown();
		((MultiPlayerGameModeAccessor) this).coffeeclient$setDestroyDelay(cooldown);
	}

	@Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$interactBlock(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		var bus = CoffeeClient.events();
		if (bus == null || !bus.has(Events.InteractBlock.class)) return;
		if (bus.post(new Events.InteractBlock(hand, hit)).isCancelled()) cir.setReturnValue(InteractionResult.FAIL);
	}
}
