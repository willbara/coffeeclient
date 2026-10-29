package dev.coffeeclient.gui;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.fx.Shapes;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.util.Colors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringDecomposer;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;

/**
 * Drawing primitives built only on {@link GuiGraphicsExtractor} (fills, gradients, text, pose transforms).
 * No raw GL, so this keeps working when the game's render backend changes.
 */
public final class Draw {

	private Draw() {
	}

	/** Text style per interface font (the vanilla font needs none). */
	private static final java.util.EnumMap<Theme.TextFont, Style> STYLES = new java.util.EnumMap<>(Theme.TextFont.class);

	static {
		for (Theme.TextFont f : Theme.TextFont.values()) {
			STYLES.put(f, f.id == null ? Style.EMPTY
				: Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath("coffeeclient", f.id))));
		}
	}

	private static Style style() {
		return STYLES.get(CoffeeClient.theme().textFont.get());
	}

	// ---- global alpha: lets a whole screen (or one widget) fade in and out ----

	private static final float[] ALPHA = new float[16];
	private static int alphaDepth;
	private static float alpha = 1f;
	private static boolean textShadow = true;

	static {
		ALPHA[0] = 1f;
	}

	/** Multiply every following draw's alpha by {@code factor} until {@link #popAlpha}. */
	public static void pushAlpha(float factor) {
		if (alphaDepth < ALPHA.length - 1) ALPHA[++alphaDepth] = alpha * Math.max(0f, Math.min(1f, factor));
		alpha = ALPHA[alphaDepth];
	}

	public static void popAlpha() {
		if (alphaDepth > 0) alphaDepth--;
		alpha = ALPHA[alphaDepth];
	}

	/** Clear the alpha stack (start of a frame), so a draw that threw mid-fade cannot dim everything after it. */
	public static void resetAlpha() {
		alphaDepth = 0;
		alpha = 1f;
	}

	/** Current global alpha multiplier. */
	public static float alpha() {
		return alpha;
	}

	/** Drop shadows under text (off on light themes, where they smudge). */
	public static void textShadow(boolean on) {
		textShadow = on;
	}

	private static int a(int color) {
		return alpha >= 1f ? color : Colors.fade(color, alpha);
	}

	/**
	 * A plain string as drawable text. Going through a Component runs bidirectional-text analysis on
	 * every draw, which profiled as a large share of HUD allocation; this keeps formatting codes (and
	 * PlayerProtect, which hooks iterateFormatted) without it.
	 */
	private static FormattedCharSequence face(String text) {
		Style style = style();
		return sink -> StringDecomposer.iterateFormatted(text, style, sink);
	}

	public static Font font() {
		return Minecraft.getInstance().font;
	}

	public static void rect(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		color = a(color);
		if (w <= 0 || h <= 0 || Colors.alpha(color) == 0) return;
		g.fill(x, y, x + w, y + h, color);
	}

	public static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		color = a(color);
		if (w <= 0 || h <= 0 || Colors.alpha(color) == 0) return;
		g.outline(x, y, w, h, color);
	}

	/** Corner radius of Glass windows and cards. */
	public static final int GLASS_RADIUS = 8;

	/** Radius of a Glass control: fully round ends on short controls (pills), capped on tall ones. */
	public static int controlRadius(int w, int h) {
		return Math.min(Math.min(w, h) / 2, 7);
	}

	/**
	 * Filled box with a 1px frame; corners follow the theme (at most a 1px notch, so small controls stay
	 * crisp). In the Glass style it is a smooth pill.
	 */
	public static void box(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, int frame) {
		if (CoffeeClient.theme().glass()) {
			int r = controlRadius(w, h);
			Shapes.roundRect(g, x, y, w, h, r, fill);
			Shapes.roundOutline(g, x, y, w, h, r, frame, Colors.fade(frame, 0.7f));
			return;
		}
		if (CoffeeClient.theme().corners.get() == Theme.Corners.SHARP || w < 6 || h < 6) {
			rect(g, x, y, w, h, fill);
			border(g, x, y, w, h, frame);
			return;
		}
		shapeFill(g, x, y, w, h, Theme.Corners.NOTCHED.insets, fill, fill);
		shapeOutline(g, x, y, w, h, Theme.Corners.NOTCHED.insets, frame);
	}

	public static void gradientV(GuiGraphicsExtractor g, int x, int y, int w, int h, int top, int bottom) {
		if (w <= 0 || h <= 0) return;
		g.fillGradient(x, y, x + w, y + h, a(top), a(bottom));
	}

	/** Horizontal gradient: the vertical gradient rotated a quarter turn. */
	public static void gradientH(GuiGraphicsExtractor g, int x, int y, int w, int h, int left, int right) {
		if (w <= 0 || h <= 0) return;
		g.pose().pushMatrix();
		g.pose().translate(x, y + h);
		g.pose().rotate((float) (-Math.PI / 2));
		g.fillGradient(0, 0, h, w, a(left), a(right));
		g.pose().popMatrix();
	}

	/** Colour at a position 0..1 along a surface. */
	@FunctionalInterface
	public interface ColorAt {
		int at(float pos);
	}

	/**
	 * Horizontal fill whose colour is sampled along its width (moving accents, rainbows). Drawn as a few
	 * gradient strips, so it stays smooth without one quad per pixel.
	 *
	 * @param from position of the left edge, so neighbouring surfaces can continue one flow
	 * @param span how much of the colour range this width covers
	 */
	public static void gradientH(GuiGraphicsExtractor g, int x, int y, int w, int h, float from, float span, ColorAt color) {
		if (w <= 0 || h <= 0) return;
		int strips = Math.max(1, Math.min(32, w / 8));
		int prevX = x;
		int prevColor = color.at(from);
		for (int i = 1; i <= strips; i++) {
			int nx = x + Math.round(w * i / (float) strips);
			int next = color.at(from + span * i / strips);
			gradientH(g, prevX, y, nx - prevX, h, prevColor, next);
			prevX = nx;
			prevColor = next;
		}
	}

	// ---- shaped panels (theme corners) ----

	/** Fill a rectangle with stepped corners ({@code insets[i]} pixels cut on row i from each edge). */
	public static void shapeFill(GuiGraphicsExtractor g, int x, int y, int w, int h, int[] insets, int top, int bottom) {
		int r = Math.min(insets.length, Math.min(w, h) / 2);
		if (r == 0) {
			gradientV(g, x, y, w, h, top, bottom);
			return;
		}
		for (int i = 0; i < r; i++) {
			float ft = i / (float) h, fb = (h - 1 - i) / (float) h;
			rect(g, x + insets[i], y + i, w - insets[i] * 2, 1, Colors.lerp(top, bottom, ft));
			rect(g, x + insets[i], y + h - 1 - i, w - insets[i] * 2, 1, Colors.lerp(top, bottom, fb));
		}
		gradientV(g, x, y + r, w, h - r * 2, Colors.lerp(top, bottom, r / (float) h), Colors.lerp(top, bottom, (h - r) / (float) h));
	}

	/** Fill the top {@code h} rows inside a shaped frame (window headers), keeping clear of the corners. */
	public static void shapeTop(GuiGraphicsExtractor g, int x, int y, int w, int h, int[] insets, int color) {
		int r = Math.min(insets.length, h);
		for (int i = 1; i < r; i++) {
			int in = Math.max(1, insets[i] + 1);
			rect(g, x + in, y + i, w - in * 2, 1, color);
		}
		int start = Math.max(1, r);
		rect(g, x + 1, y + start, w - 2, h - start, color);
	}

	/** 1px frame following the same stepped corners as {@link #shapeFill}. */
	public static void shapeOutline(GuiGraphicsExtractor g, int x, int y, int w, int h, int[] insets, int color) {
		int r = Math.min(insets.length, Math.min(w, h) / 2);
		if (r == 0) {
			border(g, x, y, w, h, color);
			return;
		}
		rect(g, x + insets[0], y, w - insets[0] * 2, 1, color);
		rect(g, x + insets[0], y + h - 1, w - insets[0] * 2, 1, color);
		for (int i = 1; i < r; i++) {
			int from = insets[i], to = Math.max(insets[i], insets[i - 1] - 1);
			int len = to - from + 1;
			rect(g, x + from, y + i, len, 1, color);
			rect(g, x + w - 1 - to, y + i, len, 1, color);
			rect(g, x + from, y + h - 1 - i, len, 1, color);
			rect(g, x + w - 1 - to, y + h - 1 - i, len, 1, color);
		}
		rect(g, x, y + r, 1, h - r * 2, color);
		rect(g, x + w - 1, y + r, 1, h - r * 2, color);
	}

	/** Accent glow: soft rings outside a rectangle (a blurred halo in the Glass style). */
	public static void glow(GuiGraphicsExtractor g, int x, int y, int w, int h, int color, float strength, int size) {
		if (strength <= 0.01f) return;
		if (CoffeeClient.theme().glass()) {
			Shapes.halo(g, x, y, w, h, GLASS_RADIUS, size * 2, Colors.withAlpha(color, Math.round(Colors.alpha(color) * 0.5f * Math.min(1f, strength))));
			return;
		}
		for (int i = 1; i <= size; i++) {
			float f = 1 - i / (float) (size + 1);
			int c = Colors.withAlpha(color, Math.round(Colors.alpha(color) * f * f * 0.45f * strength));
			border(g, x - i, y - i, w + i * 2, h + i * 2, c);
		}
	}

	/** Drop shadow below and right of a rectangle, in the theme's shadow style. */
	public static void shadow(GuiGraphicsExtractor g, Theme t, int x, int y, int w, int h) {
		if (t.glass() && t.shadow.get() != Theme.Shadow.NONE) {
			Shapes.shadow(g, x, y + 2, w, h, GLASS_RADIUS, 8, Colors.withAlpha(0, 0x60));
			return;
		}
		switch (t.shadow.get()) {
			case SOFT -> {
				for (int i = 1; i <= 4; i++) {
					int c = Colors.withAlpha(0, Math.round(0x34 * (1 - i / 5f)));
					rect(g, x + i, y + h + i - 1, w, 1, c);
					rect(g, x + w + i - 1, y + i, 1, h - 1 + i, c);
				}
			}
			case DITHER -> {
				int c = Colors.withAlpha(0, Math.round(0x90 * alpha));
				dev.coffeeclient.gui.fx.FxTextures.dither(g, x + 3, y + h, w, 3, c);
				dev.coffeeclient.gui.fx.FxTextures.dither(g, x + w, y + 3, 3, h - 3, c);
			}
			case NONE -> {
			}
		}
	}

	/** Glassy highlight over the top of a rectangle. */
	public static void gloss(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		int gh = Math.max(2, Math.round(h * 0.45f));
		gradientV(g, x, y, w, gh, Colors.withAlpha(0xFFFFFF, 0x26), Colors.withAlpha(0xFFFFFF, 0x04));
		rect(g, x + 1, y, w - 2, 1, Colors.withAlpha(0xFFFFFF, 0x40));
	}

	/** A circle outline (ripples, rings). */
	public static void ring(GuiGraphicsExtractor g, float cx, float cy, float r, float thickness, int color) {
		if (r < 0.5f || Colors.alpha(color) == 0) return;
		int seg = Math.max(8, Math.min(40, Math.round(r * 1.5f)));
		for (int s = 0; s < seg; s++) {
			double a0 = s * Math.PI * 2 / seg, a1 = (s + 1) * Math.PI * 2 / seg;
			stroke(g, cx + (float) Math.cos(a0) * r, cy + (float) Math.sin(a0) * r,
				cx + (float) Math.cos(a1) * r, cy + (float) Math.sin(a1) * r, thickness, color);
		}
	}

	/** Marching-ants outline; {@code phase} in pixels moves the dashes. */
	public static void dashedOutline(GuiGraphicsExtractor g, int x, int y, int w, int h, int dash, int phase, int color) {
		int per = w * 2 + h * 2;
		for (int p = 0; p < per; p++) {
			if (((p + phase) / dash) % 2 != 0) continue;
			int px, py;
			if (p < w) { px = x + p; py = y; }
			else if (p < w + h) { px = x + w - 1; py = y + p - w; }
			else if (p < w * 2 + h) { px = x + w - 1 - (p - w - h); py = y + h - 1; }
			else { px = x; py = y + h - 1 - (p - w * 2 - h); }
			rect(g, px, py, 1, 1, color);
		}
	}

	/**
	 * Filled quad with the corners given in order (p0 p1 p2 p3). It is drawn as the affine image of a
	 * unit square, i.e. the parallelogram through p0, p1 and p3 (the fourth corner is implied). One
	 * draw call, so projected world faces are cheap.
	 */
	public static void quad(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1,
							float x2, float y2, float x3, float y3, int color) {
		if (Colors.alpha(color) == 0) return;
		float ux = x1 - x0, uy = y1 - y0, vx = x3 - x0, vy = y3 - y0;
		float det = ux * vy - uy * vx;
		if (Math.abs(det) < 0.01f) return;
		if (det < 0) {
			// A mirrored transform flips the winding and the GUI pipeline culls the back face: swap the
			// edges so the same parallelogram is always front-facing.
			float tx = ux, ty = uy;
			ux = vx;
			uy = vy;
			vx = tx;
			vy = ty;
		}
		g.pose().pushMatrix();
		g.pose().mul(new Matrix3x2f(ux, uy, vx, vy, x0, y0));
		g.fill(0, 0, 1, 1, a(color));
		g.pose().popMatrix();
	}

	/** A straight line of the given thickness, drawn as a single quad (one draw call, unlike {@link #line}). */
	public static void stroke(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, float thickness, int color) {
		float dx = x2 - x1, dy = y2 - y1;
		float len = (float) Math.sqrt(dx * dx + dy * dy);
		if (len < 0.01f) return;
		float nx = -dy / len * thickness * 0.5f, ny = dx / len * thickness * 0.5f;
		quad(g, x1 + nx, y1 + ny, x2 + nx, y2 + ny, x2 - nx, y2 - ny, x1 - nx, y1 - ny, color);
	}

	/**
	 * A soft diagonal light band sweeping across the rect. One scissor and two quads, so it is cheap
	 * enough to run on every header.
	 *
	 * @param phase 0..1 position of the sweep
	 */
	public static void sheen(GuiGraphicsExtractor g, int x, int y, int w, int h, float phase, int color) {
		if (w <= 0 || h <= 0 || Colors.alpha(color) == 0) return;
		int band = Math.max(10, h);
		float travel = w + h * 2 + band * 2;
		float pos = -band - h + phase * travel;
		g.enableScissor(x, y, x + w, y + h);
		g.pose().pushMatrix();
		g.pose().translate(x + pos, y + h / 2f);
		g.pose().rotate(0.45f);
		int len = h * 3;
		gradientH(g, -band, -len, band, len * 2, Colors.withAlpha(color, 0), color);
		gradientH(g, 0, -len, band, len * 2, color, Colors.withAlpha(color, 0));
		g.pose().popMatrix();
		g.disableScissor();
	}

	/** Sheen phase derived from wall time; {@code offset} staggers neighbouring elements. */
	public static float sheenPhase(long timeMs, float periodSeconds, float offset) {
		float t = (timeMs % (long) (periodSeconds * 1000)) / (periodSeconds * 1000f);
		return (t + offset) % 1f;
	}

	/** The reference panel look, styled by the theme: shadow, glow, shaped fill, texture, gloss, frame. */
	public static void panel(GuiGraphicsExtractor g, Theme t, int x, int y, int w, int h) {
		panelBody(g, t, x, y, w, h);
		panelFinish(g, t, x, y, w, h);
	}

	/** Shadow, glow, fill and texture of a panel; draw headers on top, then {@link #panelFinish}. */
	public static void panelBody(GuiGraphicsExtractor g, Theme t, int x, int y, int w, int h) {
		if (t.glass()) {
			glassBody(g, t, x, y, w, h, GLASS_RADIUS);
			return;
		}
		shadow(g, t, x, y, w, h);
		glow(g, x, y, w, h, t.accent, t.glow.floatValue(), 5);
		int[] insets = t.corners.get().insets;
		shapeFill(g, x, y, w, h, insets, t.panelTop, t.panelBottom);
		int r = Math.min(insets.length, 2);
		dev.coffeeclient.gui.fx.FxTextures.tile(g, t.texture.get(), x + 1 + r, y + 1, w - 2 - r * 2, h - 2, t.textureStrength.floatValue() * 0.6f);
	}

	/** Gloss and frame of a panel. */
	public static void panelFinish(GuiGraphicsExtractor g, Theme t, int x, int y, int w, int h) {
		if (t.glass()) {
			glassRim(g, t, x, y, w, h, GLASS_RADIUS, 0f);
			return;
		}
		if (t.gloss.get()) gloss(g, x + 1, y + 1, w - 2, Math.min(h - 2, 60));
		shapeOutline(g, x, y, w, h, t.corners.get().insets, t.border);
	}

	// ---- glass ----

	/**
	 * A frosted Glass card: soft drop shadow, a faint accent halo (from the Glow setting), a translucent
	 * rounded fill and the texture kept inside the corners. Finish with {@link #glassRim}.
	 */
	public static void glassBody(GuiGraphicsExtractor g, Theme t, int x, int y, int w, int h, int r) {
		if (t.shadow.get() != Theme.Shadow.NONE) Shapes.shadow(g, x, y + 3, w, h, r, 10, Colors.withAlpha(0, 0x78));
		if (t.glow.floatValue() > 0.01f) Shapes.halo(g, x, y, w, h, r, 6, t.accent(Math.round(0x30 * t.glow.floatValue())));
		Shapes.roundRectV(g, x, y, w, h, r, t.panelTop, t.panelBottom);
		r = Shapes.fit(r, w, h);
		float tex = t.textureStrength.floatValue() * 0.6f;
		dev.coffeeclient.gui.fx.FxTextures.tile(g, t.texture.get(), x + r, y + 1, w - r * 2, h - 2, tex);
		dev.coffeeclient.gui.fx.FxTextures.tile(g, t.texture.get(), x + 1, y + r, r - 1, h - r * 2, tex);
		dev.coffeeclient.gui.fx.FxTextures.tile(g, t.texture.get(), x + w - r, y + r, r - 1, h - r * 2, tex);
	}

	/**
	 * The light catching a Glass card: a soft sheen across the top and a rim bright at the top edge,
	 * fading down the sides. {@code lit} (0..1) warms the rim toward the accent (hover, drag).
	 */
	public static void glassRim(GuiGraphicsExtractor g, Theme t, int x, int y, int w, int h, int r, float lit) {
		r = Shapes.fit(r, w, h);
		int sheenH = Math.min(h / 2, 40);
		gradientV(g, x + r, y + 1, w - r * 2, sheenH, Colors.withAlpha(0xFFFFFF, t.light ? 0x30 : 0x0E), Colors.withAlpha(0xFFFFFF, 0));
		int top = Colors.lerp(Colors.withAlpha(Colors.lerp(t.border, 0xFFFFFFFF, 0.45f), 0x90), t.accent(0xC0), lit);
		int bottom = Colors.lerp(Colors.withAlpha(t.border, 0x60), t.accent(0x70), lit);
		Shapes.roundOutline(g, x, y, w, h, r, top, bottom);
	}

	/**
	 * Highlight of a row in a selectable list: the selected row gets an accent gradient with a bar on its
	 * left (a tinted pill with an accent rim in the Glass style), a hovered one a light wash.
	 */
	public static void listHighlight(GuiGraphicsExtractor g, Theme t, int x, int y, int w, int h, boolean selected, float hover, int hoverAlpha) {
		if (t.glass()) {
			int r = Math.min(7, h / 2);
			if (selected) {
				Shapes.roundRect(g, x + 2, y + 1, w - 4, h - 2, r, t.accent(0x26));
				Shapes.roundOutline(g, x + 2, y + 1, w - 4, h - 2, r, t.accent(0x90), t.accent(0x30));
			} else if (hover > 0.01f) {
				Shapes.roundRect(g, x + 2, y + 1, w - 4, h - 2, r, Colors.withAlpha(0xFFFFFF, Math.round(0x0E * hover)));
			}
			return;
		}
		if (selected) {
			gradientH(g, x, y, w, h, t.accent(0x60), t.accent(0x18));
			rect(g, x, y, 2, h, t.accent);
		} else if (hover > 0.01f) {
			rect(g, x, y, w, h, t.accent((int) (hoverAlpha * hover)));
		}
	}

	/** Background of a HUD element: a plain fill, or a rounded one in the Glass style. */
	public static void hudBackground(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		if (CoffeeClient.theme().glass()) Shapes.roundRect(g, x, y, w, h, Math.min(5, Math.min(w, h) / 2), color);
		else rect(g, x, y, w, h, color);
	}

	/** Small vertical accent bar used left of titles and section headers. */
	public static void accentBar(GuiGraphicsExtractor g, Theme t, int x, int y, int h) {
		gradientV(g, x, y, 2, h, t.accentAt(0f), t.accentAt(0.25f));
	}

	public static void text(GuiGraphicsExtractor g, String s, int x, int y, int color) {
		color = a(color);
		if (Colors.alpha(color) < 4) return;
		g.text(font(), face(s), x, y, color, textShadow);
	}

	public static void textNoShadow(GuiGraphicsExtractor g, String s, int x, int y, int color) {
		color = a(color);
		if (Colors.alpha(color) < 4) return;
		g.text(font(), face(s), x, y, color, false);
	}

	public static void textRight(GuiGraphicsExtractor g, String s, int rightX, int y, int color) {
		text(g, s, rightX - width(s), y, color);
	}

	public static void textCentered(GuiGraphicsExtractor g, String s, int centerX, int y, int color) {
		text(g, s, centerX - width(s) / 2, y, color);
	}

	/** Text at a scale, positioned by its top-left corner in unscaled coordinates. */
	public static void textScaled(GuiGraphicsExtractor g, String s, float x, float y, int color, float scale) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		color = a(color);
		if (Colors.alpha(color) >= 4) g.text(font(), face(s), 0, 0, color, textShadow);
		g.pose().popMatrix();
	}

	private static final java.util.HashMap<String, Integer> WIDTHS = new java.util.HashMap<>();
	private static Theme.TextFont widthsFont;

	/** Render-thread only. Cached because measuring builds a styled component per call. */
	public static int width(String s) {
		Theme.TextFont current = CoffeeClient.theme().textFont.get();
		if (current != widthsFont || WIDTHS.size() > 4096) {
			WIDTHS.clear();
			widthsFont = current;
		}
		Integer cached = WIDTHS.get(s);
		if (cached != null) return cached;
		int w = font().width(face(s));
		WIDTHS.put(s, w);
		return w;
	}

	public static int lineHeight() {
		return font().lineHeight;
	}

	/** Trim with an ellipsis to fit {@code maxWidth}. */
	public static String trim(String s, int maxWidth) {
		Font f = font();
		if (f.width(face(s)) <= maxWidth) return s;
		String ellipsis = "...";
		int ew = f.width(face(ellipsis));
		if (maxWidth <= ew) return "";
		return substringByWidth(s, maxWidth - ew) + ellipsis;
	}

	/** Break {@code s} into lines no wider than {@code maxWidth}, between words where it can. */
	public static java.util.List<String> wrap(String s, int maxWidth) {
		java.util.List<String> lines = new java.util.ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : s.split(" ")) {
			String next = line.isEmpty() ? word : line + " " + word;
			if (width(next) <= maxWidth || line.isEmpty()) {
				line.setLength(0);
				line.append(next);
			} else {
				lines.add(line.toString());
				line.setLength(0);
				line.append(word);
			}
		}
		if (!line.isEmpty()) lines.add(line.toString());
		return lines;
	}

	public static String substringByWidth(String s, int maxWidth) {
		return font().substrByWidth(Component.literal(s).withStyle(style()), maxWidth).getString();
	}

	/**
	 * Straight line drawn as small squares (for HUD-projected trails and tracers). Squares that sit in
	 * a row along the line's main direction are merged into one rectangle, the part off screen is
	 * clipped away first, and the whole line goes to the GUI as a single element.
	 */
	public static void line(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, int thickness, int color) {
		color = a(color);
		if (Colors.alpha(color) == 0) return;
		int t = Math.max(1, thickness);
		float[] c = clip(x1, y1, x2, y2, -t, -t, g.guiWidth() + t, g.guiHeight() + t);
		if (c == null) return;
		x1 = c[0];
		y1 = c[1];
		x2 = c[2];
		y2 = c[3];
		GuiElementSink sink = (GuiElementSink) g;
		PixelRects rects = new PixelRects(g.pose(), sink.coffeeclient$scissor());
		float dx = x2 - x1, dy = y2 - y1;
		int steps = (int) Math.min(400, Math.max(1, Math.hypot(dx, dy) / t));
		boolean horizontal = Math.abs(dx) >= Math.abs(dy);
		// Current run: squares sharing the minor coordinate, from runMin to runMax on the major axis.
		int runMinor = 0, runMin = 0, runMax = 0;
		boolean open = false;
		for (int i = 0; i <= steps; i++) {
			float f = i / (float) steps;
			int px = Math.round(x1 + dx * f), py = Math.round(y1 + dy * f);
			int major = horizontal ? px : py, minor = horizontal ? py : px;
			if (open && minor == runMinor && major >= runMin - t && major <= runMax + t) {
				runMin = Math.min(runMin, major);
				runMax = Math.max(runMax, major);
				continue;
			}
			if (open) lineRun(rects, horizontal, runMinor, runMin, runMax, t, color);
			runMinor = minor;
			runMin = runMax = major;
			open = true;
		}
		if (open) lineRun(rects, horizontal, runMinor, runMin, runMax, t, color);
		if (!rects.isEmpty()) sink.coffeeclient$addElement(rects);
	}

	private static void lineRun(PixelRects rects, boolean horizontal, int minor, int min, int max, int t, int color) {
		if (horizontal) rects.add(min, minor, max - min + t, t, color);
		else rects.add(minor, min, t, max - min + t, color);
	}

	/** The part of a segment inside a rectangle (Liang-Barsky), or null when none of it is. */
	private static float[] clip(float x1, float y1, float x2, float y2, float minX, float minY, float maxX, float maxY) {
		float dx = x2 - x1, dy = y2 - y1;
		float t0 = 0, t1 = 1;
		float[] p = {-dx, dx, -dy, dy};
		float[] q = {x1 - minX, maxX - x1, y1 - minY, maxY - y1};
		for (int i = 0; i < 4; i++) {
			if (p[i] == 0) {
				if (q[i] < 0) return null;
				continue;
			}
			float r = q[i] / p[i];
			if (p[i] < 0) {
				if (r > t1) return null;
				t0 = Math.max(t0, r);
			} else {
				if (r < t0) return null;
				t1 = Math.min(t1, r);
			}
		}
		return new float[] {x1 + dx * t0, y1 + dy * t0, x1 + dx * t1, y1 + dy * t1};
	}

	// ---- pixel icons (drawn with fills so they match the pixel font) ----

	/** Two overlapping squares, 7x8. */
	public static void copyIcon(GuiGraphicsExtractor g, int x, int y, int color) {
		border(g, x + 2, y, 5, 6, color);
		border(g, x, y + 2, 5, 6, color);
	}

	/** Thin diagonal cross. */
	public static void cross(GuiGraphicsExtractor g, int x, int y, int size, int color) {
		for (int i = 0; i < size; i++) {
			rect(g, x + i, y + i, 1, 1, color);
			rect(g, x + size - 1 - i, y + i, 1, 1, color);
		}
	}

	/** Solid triangle arrow pointing left (dir = -1) or right (dir = 1); height 2*size-1. */
	public static void arrow(GuiGraphicsExtractor g, int x, int y, int size, int dir, int color) {
		for (int i = 0; i < size; i++) {
			int col = dir > 0 ? x + i : x + size - 1 - i;
			int span = size - i;
			rect(g, col, y + (size - span), 1, span * 2 - 1, color);
		}
	}

	/** Downward-pointing triangle. */
	public static void arrowDown(GuiGraphicsExtractor g, int x, int y, int size, int color) {
		for (int i = 0; i < size; i++) {
			rect(g, x + i, y + i, (size - i) * 2 - 1, 1, color);
		}
	}

	public static void plus(GuiGraphicsExtractor g, int x, int y, int size, int color) {
		int mid = size / 2;
		rect(g, x, y + mid, size, 1, color);
		rect(g, x + mid, y, 1, size, color);
	}

	public static void minus(GuiGraphicsExtractor g, int x, int y, int size, int color) {
		rect(g, x, y + size / 2, size, 1, color);
	}

	public static void check(GuiGraphicsExtractor g, int x, int y, int color) {
		rect(g, x, y + 3, 1, 2, color);
		rect(g, x + 1, y + 4, 1, 2, color);
		rect(g, x + 2, y + 3, 1, 2, color);
		rect(g, x + 3, y + 2, 1, 2, color);
		rect(g, x + 4, y + 1, 1, 2, color);
		rect(g, x + 5, y, 1, 2, color);
	}

	/** Open eye (visible) or struck-through eye, 9x5. */
	public static void eye(GuiGraphicsExtractor g, int x, int y, boolean open, int color) {
		rect(g, x + 2, y, 5, 1, color);
		rect(g, x + 2, y + 4, 5, 1, color);
		rect(g, x, y + 2, 1, 1, color);
		rect(g, x + 1, y + 1, 1, 1, color);
		rect(g, x + 1, y + 3, 1, 1, color);
		rect(g, x + 8, y + 2, 1, 1, color);
		rect(g, x + 7, y + 1, 1, 1, color);
		rect(g, x + 7, y + 3, 1, 1, color);
		if (open) rect(g, x + 3, y + 1, 3, 3, color);
		else cross(g, x + 2, y - 1, 7, color);
	}
}
