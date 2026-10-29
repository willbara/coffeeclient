package dev.coffeeclient.gui.fx;

import com.mojang.blaze3d.platform.NativeImage;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.util.Colors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;

import java.util.HashMap;
import java.util.Map;

/**
 * Smooth (anti-aliased) round shapes for the Glass style: discs, rings and soft blurred halos. Each is a
 * white alpha mask painted once per size in real screen pixels and tinted through the blit colour, so
 * edges stay clean at any GUI scale. Rounded rectangles are four mask corners around plain fills, and
 * shadows are nine-sliced from one blurred disc. Render thread only.
 */
public final class Shapes {
	private enum Kind { DISC, RING, SHADOW, HALO }

	private record Key(Kind kind, int r, int t) {
	}

	/** Cached masks by kind and pixel size; cleared (and released) when it grows past this. */
	private static final int MAX_CACHED = 160;
	private static final Map<Key, Identifier> CACHE = new HashMap<>();
	/** Evicted masks, released a moment later so nothing still queued for this frame loses its texture. */
	private static final java.util.List<Identifier> RETIRED = new java.util.ArrayList<>();
	private static long retiredAt;
	private static int serial;
	private static boolean failed;

	private Shapes() {
	}

	/** Real screen pixels per GUI unit at the current pose (GUI scale times any screen scaling). */
	public static float pixelScale(GuiGraphicsExtractor g) {
		Matrix3x2f m = g.pose();
		float s = (float) Math.sqrt(m.m00() * m.m00() + m.m01() * m.m01());
		return Math.max(0.25f, s * Minecraft.getInstance().getWindow().getGuiScale());
	}

	// ---- masks ----

	private static Identifier mask(Kind kind, int r, int t) {
		if (failed) return null;
		Key key = new Key(kind, r, t);
		Identifier id = CACHE.get(key);
		if (id != null) return id;
		var textures = Minecraft.getInstance().getTextureManager();
		if (!RETIRED.isEmpty() && System.currentTimeMillis() - retiredAt > 1000) {
			RETIRED.forEach(textures::release);
			RETIRED.clear();
		}
		if (CACHE.size() >= MAX_CACHED && RETIRED.isEmpty()) {
			RETIRED.addAll(CACHE.values());
			retiredAt = System.currentTimeMillis();
			CACHE.clear();
		}
		try {
			int size = kind == Kind.SHADOW || kind == Kind.HALO ? (r + t) * 2 : r * 2;
			NativeImage img = new NativeImage(size, size, true);
			float c = size / 2f;
			for (int y = 0; y < size; y++) {
				for (int x = 0; x < size; x++) {
					float d = (float) Math.hypot(x + 0.5f - c, y + 0.5f - c);
					img.setPixel(x, y, ((int) (coverage(kind, d, r, t) * 255 + 0.5f) << 24) | 0xFFFFFF);
				}
			}
			String path = "fx/shape_" + kind.name().toLowerCase(java.util.Locale.ROOT) + "_" + serial++;
			id = Identifier.fromNamespaceAndPath("coffeeclient", path);
			textures.register(id, new FxTextures.Tex("coffeeclient:" + path, img, true));
			CACHE.put(key, id);
			return id;
		} catch (Throwable e) {
			failed = true;
			CoffeeClient.LOG.warn("Glass shapes unavailable: {}", e.toString());
			return null;
		}
	}

	/** Alpha 0..1 of a mask pixel {@code d} pixels from the centre. */
	private static float coverage(Kind kind, float d, int r, int t) {
		return switch (kind) {
			case DISC -> clamp01(r - d + 0.5f);
			case RING -> clamp01(r - d + 0.5f) - clamp01(r - t - d + 0.5f);
			// t is the blur spread: solid inside the radius, a soft falloff outside it
			case SHADOW -> d <= r ? 1f : falloff((d - r) / t);
			case HALO -> falloff(Math.max(0f, d - r) / t) * clamp01(d - r + 0.5f);
		};
	}

	private static float falloff(float u) {
		if (u >= 1f) return 0f;
		float f = 1f - u;
		return f * f * (float) Math.exp(-2.2f * u * u);
	}

	private static float clamp01(float v) {
		return Math.max(0f, Math.min(1f, v));
	}

	private static int tint(int color) {
		float a = Draw.alpha();
		return a >= 1f ? color : Colors.fade(color, a);
	}

	private static void blit(GuiGraphicsExtractor g, Identifier id, int x, int y, float u, float v, int w, int h, int srcW, int srcH, int tex, int color) {
		if (w <= 0 || h <= 0) return;
		color = tint(color);
		if (Colors.alpha(color) == 0) return;
		g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, u, v, w, h, srcW, srcH, tex, tex, color);
	}

	/** Largest corner radius that fits a {@code w} by {@code h} box. */
	public static int fit(int r, int w, int h) {
		return Math.max(0, Math.min(r, Math.min(w, h) / 2));
	}

	// ---- rounded rectangles ----

	public static void roundRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
		roundRectV(g, x, y, w, h, r, color, color);
	}

	/** Rounded rectangle with a vertical gradient. */
	public static void roundRectV(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int top, int bottom) {
		if (w <= 0 || h <= 0) return;
		r = fit(r, w, h);
		int rPx = Math.round(r * pixelScale(g));
		Identifier id = r > 0 && rPx > 0 ? mask(Kind.DISC, rPx, 0) : null;
		if (id == null) {
			Draw.gradientV(g, x, y, w, h, top, bottom);
			return;
		}
		float edge = r / (float) h;
		int upper = Colors.lerp(top, bottom, edge * 0.5f), lower = Colors.lerp(top, bottom, 1 - edge * 0.5f);
		Draw.gradientV(g, x + r, y, w - r * 2, h, top, bottom);
		int sideTop = Colors.lerp(top, bottom, edge), sideBottom = Colors.lerp(top, bottom, 1 - edge);
		Draw.gradientV(g, x, y + r, r, h - r * 2, sideTop, sideBottom);
		Draw.gradientV(g, x + w - r, y + r, r, h - r * 2, sideTop, sideBottom);
		corners(g, id, x, y, w, h, r, rPx, rPx * 2, upper, upper, lower, lower);
	}

	/** A 1-unit rim around a rounded rectangle, fading from {@code top} to {@code bottom}. */
	public static void roundOutline(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int top, int bottom) {
		if (w <= 0 || h <= 0) return;
		r = fit(r, w, h);
		float scale = pixelScale(g);
		int rPx = Math.round(r * scale);
		Identifier id = r > 1 && rPx > 1 ? mask(Kind.RING, rPx, Math.max(1, Math.round(scale))) : null;
		if (id == null) {
			Draw.rect(g, x, y, w, 1, top);
			Draw.rect(g, x, y + h - 1, w, 1, bottom);
			Draw.gradientV(g, x, y + 1, 1, h - 2, top, bottom);
			Draw.gradientV(g, x + w - 1, y + 1, 1, h - 2, top, bottom);
			return;
		}
		float edge = r / (float) h;
		Draw.rect(g, x + r, y, w - r * 2, 1, top);
		Draw.rect(g, x + r, y + h - 1, w - r * 2, 1, bottom);
		int sideTop = Colors.lerp(top, bottom, edge), sideBottom = Colors.lerp(top, bottom, 1 - edge);
		Draw.gradientV(g, x, y + r, 1, h - r * 2, sideTop, sideBottom);
		Draw.gradientV(g, x + w - 1, y + r, 1, h - r * 2, sideTop, sideBottom);
		int upper = Colors.lerp(top, bottom, edge * 0.5f), lower = Colors.lerp(top, bottom, 1 - edge * 0.5f);
		corners(g, id, x, y, w, h, r, rPx, rPx * 2, upper, upper, lower, lower);
	}

	private static void corners(GuiGraphicsExtractor g, Identifier id, int x, int y, int w, int h, int r, int rPx, int tex,
								int tl, int tr, int bl, int br) {
		blit(g, id, x, y, 0, 0, r, r, rPx, rPx, tex, tl);
		blit(g, id, x + w - r, y, rPx, 0, r, r, rPx, rPx, tex, tr);
		blit(g, id, x, y + h - r, 0, rPx, r, r, rPx, rPx, tex, bl);
		blit(g, id, x + w - r, y + h - r, rPx, rPx, r, r, rPx, rPx, tex, br);
	}

	// ---- soft shadows and glows ----

	/** A blurred, filled rounded rectangle spreading {@code spread} units past the box (drop shadows). */
	public static void shadow(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int spread, int color) {
		soft(g, Kind.SHADOW, x, y, w, h, r, spread, color);
	}

	/** Like {@link #shadow} but hollow: light only outside the box, so translucent surfaces keep their colour (glows). */
	public static void halo(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int spread, int color) {
		soft(g, Kind.HALO, x, y, w, h, r, spread, color);
	}

	private static void soft(GuiGraphicsExtractor g, Kind kind, int x, int y, int w, int h, int r, int spread, int color) {
		if (w <= 0 || h <= 0 || spread <= 0 || Colors.alpha(tint(color)) == 0) return;
		r = fit(r, w, h);
		float scale = pixelScale(g);
		int rPx = Math.round(r * scale), sPx = Math.max(1, Math.round(spread * scale));
		Identifier id = mask(kind, rPx, sPx);
		if (id == null) return;
		int e = r + spread, ePx = rPx + sPx, tex = ePx * 2;
		int ox = x - spread, oy = y - spread, ow = w + spread * 2, oh = h + spread * 2;
		corners(g, id, ox, oy, ow, oh, e, ePx, tex, color, color, color, color);
		// edges stretch the mask's middle column and row
		float mid = ePx - 0.5f;
		blit(g, id, ox + e, oy, mid, 0, ow - e * 2, e, 1, ePx, tex, color);
		blit(g, id, ox + e, oy + oh - e, mid, ePx, ow - e * 2, e, 1, ePx, tex, color);
		blit(g, id, ox, oy + e, 0, mid, e, oh - e * 2, ePx, 1, tex, color);
		blit(g, id, ox + ow - e, oy + e, ePx, mid, e, oh - e * 2, ePx, 1, tex, color);
		if (kind == Kind.SHADOW) Draw.rect(g, ox + e, oy + e, ow - e * 2, oh - e * 2, color);
	}

	// ---- discs ----

	/** A filled circle of radius {@code r} centred on a fractional point (knobs that glide). */
	public static void disc(GuiGraphicsExtractor g, float cx, float cy, int r, int color) {
		if (r <= 0) return;
		int rPx = Math.round(r * pixelScale(g));
		Identifier id = rPx > 0 ? mask(Kind.DISC, rPx, 0) : null;
		if (id == null) {
			Draw.rect(g, Math.round(cx) - r, Math.round(cy) - r, r * 2, r * 2, color);
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(cx - r, cy - r);
		blit(g, id, 0, 0, 0, 0, r * 2, r * 2, rPx * 2, rPx * 2, rPx * 2, color);
		g.pose().popMatrix();
	}

	/** A soft glow around a circle (an "on" light). */
	public static void glowDot(GuiGraphicsExtractor g, float cx, float cy, int r, int spread, int color) {
		g.pose().pushMatrix();
		g.pose().translate(cx - Math.round(cx), cy - Math.round(cy));
		int x = Math.round(cx) - r, y = Math.round(cy) - r;
		halo(g, x, y, r * 2, r * 2, r, spread, color);
		g.pose().popMatrix();
	}
}
