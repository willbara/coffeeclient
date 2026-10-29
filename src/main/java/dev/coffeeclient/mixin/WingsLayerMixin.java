package dev.coffeeclient.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.module.modules.cosmetics.Wings;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.WingsLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wings cosmetic, "Replace elytra": skips the vanilla elytra layer on the players the wings show on. */
@Mixin(WingsLayer.class)
public abstract class WingsLayerMixin {
	@Inject(method = "submit", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$replaceElytra(PoseStack pose, SubmitNodeCollector nodes, int light, HumanoidRenderState state, float yRot, float xRot, CallbackInfo ci) {
		if (state instanceof AvatarRenderState avatar && Wings.replacesElytra(avatar.id)) ci.cancel();
	}
}
