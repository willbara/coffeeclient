package dev.coffeeclient.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.module.modules.render.NoEffect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NoEffect's screen overlays: the fire, underwater and in-wall textures drawn over the view. */
@Mixin(ScreenEffectRenderer.class)
abstract class ScreenEffectRendererMixin {
	@Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
	private static void coffeeclient$noFire(PoseStack poseStack, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
		if (NoEffect.hidesFireOverlay()) ci.cancel();
	}

	@Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
	private static void coffeeclient$noWater(Minecraft mc, PoseStack poseStack, SubmitNodeCollector collector, CallbackInfo ci) {
		if (NoEffect.hidesWaterOverlay()) ci.cancel();
	}

	@Inject(method = "submitBlockSprite", at = @At("HEAD"), cancellable = true)
	private static void coffeeclient$noWall(TextureAtlasSprite sprite, PoseStack poseStack, SubmitNodeCollector collector, int light, CallbackInfo ci) {
		if (NoEffect.hidesWallOverlay()) ci.cancel();
	}
}
