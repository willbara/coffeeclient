package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.coffeeclient.module.modules.render.ItemPhysics;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ItemPhysics: spin speed, no bobbing, and flat items lying on the ground. */
@Mixin(ItemEntityRenderer.class)
abstract class ItemEntityRendererMixin {
	@WrapOperation(method = "submit", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;getSpin(FF)F"))
	private float coffeeclient$spin(float age, float bob, Operation<Float> original) {
		if (!ItemPhysics.active()) return original.call(age, bob);
		return original.call(age * ItemPhysics.self().spinSpeed.floatValue(), bob);
	}

	@ModifyArg(method = "submit", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V", ordinal = 0), index = 1)
	private float coffeeclient$noBob(float y, @Local(argsOnly = true) ItemEntityRenderState state) {
		if (!ItemPhysics.active() || !ItemPhysics.self().noBob.get()) return y;
		float bob = Mth.sin(state.ageInTicks / 10.0F + state.bobOffset) * 0.1F + 0.1F;
		return y - bob;
	}

	@Inject(method = "submit", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/entity/ItemEntityRenderer;submitMultipleFromCount(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/ItemClusterRenderState;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/phys/AABB;)V"))
	private void coffeeclient$layFlat(ItemEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		if (!ItemPhysics.active() || !ItemPhysics.self().layFlat.get()) return;
		AABB box = state.item.getModelBoundingBox();
		if (box.maxZ - box.minZ > 0.1) return; // block models stay upright
		float minOffsetY = -((float) box.minY) + 0.0625F;
		poseStack.translate(0.0F, -minOffsetY + 0.04F, 0.0F);
		poseStack.mulPose(Axis.XP.rotationDegrees(90));
	}
}
