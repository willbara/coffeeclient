package dev.coffeeclient.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.module.modules.render.NoEffect;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NoEffect: signs keep their board but lose their writing. */
@Mixin(AbstractSignRenderer.class)
abstract class AbstractSignRendererMixin {
	@Inject(method = "submitSignText", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$noSignText(SignRenderState state, PoseStack poseStack, SubmitNodeCollector collector, SignText text, CallbackInfo ci) {
		if (NoEffect.hides(m -> m.signText)) ci.cancel();
	}
}
