package dev.coffeeclient.gui.fx;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.anim.Motion;
import dev.coffeeclient.gui.theme.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Overlay textures painted in code at first use (no art files to ship). Each is white or black with
 * varying alpha, so it can be tinted and faded through the blit colour, and tiles seamlessly through
 * the texture's repeat sampler. Render thread only.
 */
public final class FxTextures {
	private static final int SIZE = 64;
	private static final Map<Theme.Texture, Identifier> TILES = new EnumMap<>(Theme.Texture.class);
	private static Identifier vignette;
	private static Identifier dither;
	private static boolean failed;

	private FxTextures() {
	}

	/** A texture whose sampler can be switched to linear filtering (for stretched gradients). */
	static final class Tex extends DynamicTexture {
		Tex(String label, NativeImage image, boolean linear) {
			super(() -> label, image);
			if (linear) this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		}
	}

	private static void init() {
		if (failed || !TILES.isEmpty()) return;
		try {
			for (Theme.Texture t : Theme.Texture.values()) {
				if (t == Theme.Texture.NONE) continue;
				NativeImage img = new NativeImage(SIZE, SIZE, true);
				for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) img.setPixel(x, y, pixel(t, x, y));
				TILES.put(t, register("fx/" + t.name().toLowerCase(Locale.ROOT), img, false));
			}
			NativeImage vig = new NativeImage(128, 128, true);
			for (int y = 0; y < 128; y++) {
				for (int x = 0; x < 128; x++) {
					float dx = (x + 0.5f) / 64f - 1, dy = (y + 0.5f) / 64f - 1;
					float d = (float) Math.sqrt(dx * dx * 0.8f + dy * dy * 1.1f);
					float a = Motion.clamp01((d - 0.45f) / 0.75f);
					vig.setPixel(x, y, argb((int) (a * a * 255), 0xFFFFFF));
				}
			}
			vignette = register("fx/vignette", vig, true);
			NativeImage dit = new NativeImage(SIZE, SIZE, true);
			for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) dit.setPixel(x, y, ((x + y) & 1) == 0 ? argb(255, 0) : 0);
			dither = register("fx/dither_shadow", dit, false);
		} catch (Throwable e) {
			failed = true;
			CoffeeClient.LOG.warn("Interface textures unavailable: {}", e.toString());
		}
	}

	private static Identifier register(String path, NativeImage img, boolean linear) {
		Identifier id = Identifier.fromNamespaceAndPath("coffeeclient", path);
		Minecraft.getInstance().getTextureManager().register(id, new Tex("coffeeclient:" + path, img, linear));
		return id;
	}

	private static int argb(int a, int rgb) {
		return (Math.max(0, Math.min(255, a)) << 24) | (rgb & 0xFFFFFF);
	}

	/** One pixel of a tile. White pixels lighten, black ones darken. */
	private static int pixel(Theme.Texture t, int x, int y) {
		float n = Motion.hash(x, y);
		return switch (t) {
			case GRAIN -> n > 0.5f ? argb((int) ((n - 0.5f) * 2 * 70), 0xFFFFFF) : argb((int) ((0.5f - n) * 2 * 70), 0);
			case SCANLINES -> (y & 1) == 0 ? argb(110, 0) : argb(8, 0xFFFFFF);
			case DITHER -> ((x ^ y) & 1) == 0 && ((x / 2 + y / 2) & 1) == 0 ? argb(60, 0xFFFFFF) : 0;
			case PAPER -> {
				// soft fibres: smoothed noise plus faint horizontal streaks
				float s = (Motion.hash(x / 4, y / 4) + Motion.hash(x / 2 + 31, y / 2) + n) / 3f;
				float streak = Motion.hash(y * 7 + 3) > 0.85f ? 0.25f : 0f;
				int a = (int) (Math.abs(s - 0.5f) * 120 + streak * 60);
				yield s > 0.5f ? argb(a, 0xFFFFFF) : argb(a, 0);
			}
			case CARBON -> {
				// 4px woven checker with a highlight on one edge of each cell
				int cx = (x / 4) & 1, cy = (y / 4) & 1;
				boolean weave = cx == cy;
				int edge = weave ? x % 4 : y % 4;
				int a = weave ? 70 : 120;
				yield edge == 0 ? argb(40, 0xFFFFFF) : argb(a, 0);
			}
			case HEX -> {
				// hexagon outlines on a 16 x ~9 grid (staggered rows)
				int row = y / 8;
				float ox = (row & 1) == 0 ? 0 : 8;
				float lx = ((x + ox) % 16) - 8, ly = (y % 8) - 4;
				float d = Math.max(Math.abs(lx) * 0.577f + Math.abs(ly) * 0.5f * 2, Math.abs(ly) * 2);
				yield Math.abs(d - 7f) < 0.9f || (y % 8 == 0 && Math.abs(lx) < 3) ? argb(70, 0xFFFFFF) : argb(18, 0);
			}
			case FROST -> {
				float s = (Motion.hash(x / 3, y / 3) * 0.6f + n * 0.4f);
				yield argb((int) (s * s * 90), 0xFFFFFF);
			}
			case NONE -> 0;
		};
	}

	/** Tile {@code type} over a rectangle at {@code strength} (0..1). Grain re-rolls 24 times a second. */
	public static void tile(GuiGraphicsExtractor g, Theme.Texture type, int x, int y, int w, int h, float strength) {
		if (type == Theme.Texture.NONE || strength <= 0.01f || w <= 0 || h <= 0) return;
		init();
		Identifier id = TILES.get(type);
		if (id == null) return;
		float u = 0, v = 0;
		if (type == Theme.Texture.GRAIN && CoffeeClient.theme().motion()) {
			int frame = (int) (Motion.time() * 24);
			u = (int) (Motion.hash(frame) * SIZE);
			v = (int) (Motion.hash(frame + 7) * SIZE);
		}
		int a = (int) (Motion.clamp01(strength) * 255 * dev.coffeeclient.gui.Draw.alpha());
		g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, u + x, v + y, w, h, SIZE, SIZE, (a << 24) | 0xFFFFFF);
	}

	/** Darkened edges over the whole rectangle. */
	public static void vignette(GuiGraphicsExtractor g, int x, int y, int w, int h, float strength) {
		vignette(g, x, y, w, h, strength, 0x000000);
	}

	/** Edges tinted with {@code rgb} (damage flashes, bursts). */
	public static void vignette(GuiGraphicsExtractor g, int x, int y, int w, int h, float strength, int rgb) {
		if (strength <= 0.01f || w <= 0 || h <= 0) return;
		init();
		if (vignette == null) return;
		int a = (int) (Motion.clamp01(strength) * 255 * dev.coffeeclient.gui.Draw.alpha());
		g.blit(RenderPipelines.GUI_TEXTURED, vignette, x, y, 0, 0, w, h, 128, 128, 128, 128, (a << 24) | (rgb & 0xFFFFFF));
	}

	/** Checkerboard shadow block (retro drop shadow). */
	public static void dither(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		if (w <= 0 || h <= 0) return;
		init();
		if (dither == null) return;
		g.blit(RenderPipelines.GUI_TEXTURED, dither, x, y, x, y, w, h, SIZE, SIZE, color);
	}
}
