package dev.coffeeclient.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.gui.font.FontTexture;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * Smooth (linear) sampling for the client's TrueType fonts. Vanilla samples every glyph sheet with
 * nearest filtering, which suits pixel fonts but leaves scaled TrueType text jagged and broken-looking.
 * Pixel-style fonts (Minecraft, Pixelation, Terminal) keep nearest filtering.
 */
@Mixin(FontTexture.class)
abstract class FontTextureMixin extends AbstractTexture {
	@Inject(method = "<init>", at = @At("RETURN"))
	private void coffeeclient$smoothFonts(Supplier<String> label, GlyphRenderTypes renderTypes, boolean colored, CallbackInfo ci) {
		String name = label.get();
		if (name.startsWith("coffeeclient:") && !name.contains("pixelation") && !name.contains("terminal")) {
			this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		}
	}
}
