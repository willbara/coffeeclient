package dev.coffeeclient.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.render.Freecam;
import dev.coffeeclient.module.modules.render.HandView;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** HandView: transform the first-person hands before they are drawn. */
@Mixin(ItemInHandRenderer.class)
abstract class ItemInHandRendererMixin {
	@Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$handView(float frameInterp, PoseStack poseStack, SubmitNodeCollector collector, LocalPlayer player, int lightCoords, CallbackInfo ci) {
		if (ModuleManager.on(Freecam.class) && !CoffeeClient.modules().get(Freecam.class).renderHands()) {
			ci.cancel();
			return;
		}
		HandView.apply(poseStack);
	}

	/** HandView per hand: right after this hand pushes its own pose. */
	@Inject(method = "submitArmWithItem", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", ordinal = 0, shift = At.Shift.AFTER))
	private void coffeeclient$handViewPerHand(AbstractClientPlayer player, float frameInterp, float xRot, InteractionHand hand, float attack, ItemStack itemStack,
											  float inverseArmHeight, PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, CallbackInfo ci) {
		HandView.applyHand(poseStack, hand, attack);
	}

	/** HandView's swing modifier: remap where in the swing each hand is drawn. */
	@com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "submitHandsWithItems", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/player/LocalPlayer;getAttackAnim(F)F"))
	private float coffeeclient$swingProgress(float attack) {
		return HandView.swingProgress(attack);
	}
}
