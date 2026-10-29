package dev.coffeeclient.module.modules.render;

import dev.coffeeclient.mixin.GameRendererAccessor;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.EnumSetting;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;

import java.util.Locale;

/**
 * Full-screen looks applied to the world (the HUD and menus stay clean): a warm coffee grade, film noir,
 * an old CRT, retro dithering, newsprint halftone, pen-and-ink, lens fringing, a cinematic vignette,
 * high contrast (low: a contrast boost, medium: posterised with edges, high: black and white with
 * edges) or inverted colours.
 */
public final class ScreenFilter extends Module {
	public enum Mode { WARM_ROAST, NOIR, CRT, DITHER, HALFTONE, INK, CHROMATIC, VIGNETTE, HIGH_CONTRAST, INVERT }

	public enum Intensity { LOW, MEDIUM, HIGH }

	public final EnumSetting<Mode> mode = setting(new EnumSetting<>("Filter", "The look applied to the world", Mode.WARM_ROAST));
	public final EnumSetting<Intensity> intensity = setting(new EnumSetting<>("Intensity", "How strongly the filter is mixed in", Intensity.MEDIUM));

	public ScreenFilter() {
		super("ScreenFilter", Category.RENDER, "Shader looks for the world: warm roast, noir, CRT, dither, halftone, ink, chromatic, vignette, high contrast, invert.");
	}

	private Identifier wanted() {
		String name = mode.get().name().toLowerCase(Locale.ROOT) + "_" + intensity.get().name().toLowerCase(Locale.ROOT);
		return Identifier.fromNamespaceAndPath("coffeeclient", name);
	}

	@Override
	public String infoString() {
		return mode.display();
	}

	@Override
	public void onTick() {
		GameRenderer renderer = mc().gameRenderer;
		Identifier id = wanted();
		// re-applied if something else (spectating a mob, another mod) replaced it
		if (!id.equals(renderer.currentPostEffect())) ((GameRendererAccessor) renderer).coffeeclient$setPostEffect(id);
	}

	@Override
	protected void onDisable() {
		GameRenderer renderer = mc().gameRenderer;
		Identifier current = renderer.currentPostEffect();
		if (current != null && current.getNamespace().equals("coffeeclient")) renderer.clearPostEffect();
	}
}
