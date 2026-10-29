package dev.coffeeclient.mixin;

import dev.coffeeclient.module.modules.render.NoEffect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NoEffect's GUI dimming: the dark gradient drawn behind screens opened in a world. */
@Mixin(Screen.class)
abstract class ScreenMixin {
	@Inject(method = "extractTransparentBackground", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$noDim(GuiGraphicsExtractor graphics, CallbackInfo ci) {
		if (Minecraft.getInstance().level != null && NoEffect.hidesGuiDim()) ci.cancel();
	}
}
