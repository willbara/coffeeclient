package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.module.modules.render.HandView;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** HandView: restyle the first-person arm (opacity, tint, glint, cham fill) where it is submitted. */
@Mixin(AvatarRenderer.class)
abstract class AvatarRendererMixin {
	@WrapOperation(method = "renderHand", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModelPart(Lnet/minecraft/client/model/geom/ModelPart;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IILnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V"))
	private void coffeeclient$styleHand(SubmitNodeCollector collector, ModelPart arm, PoseStack pose, RenderType type, int light, int overlay,
										TextureAtlasSprite sprite, Operation<Void> original) {
		if (HandView.restylesHand()) HandView.submitHand(collector, arm, pose, type, light, overlay);
		else original.call(collector, arm, pose, type, light, overlay, sprite);
	}
}
