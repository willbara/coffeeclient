package dev.coffeeclient.gui.theme;

import dev.coffeeclient.gui.fx.Backdrop;
import dev.coffeeclient.gui.theme.Theme.AccentMode;
import dev.coffeeclient.gui.theme.Theme.Corners;
import dev.coffeeclient.gui.theme.Theme.MotionStyle;
import dev.coffeeclient.gui.theme.Theme.Shadow;
import dev.coffeeclient.gui.theme.Theme.Style;
import dev.coffeeclient.gui.theme.Theme.TextFont;
import dev.coffeeclient.gui.theme.Theme.Texture;
import dev.coffeeclient.gui.theme.Theme.Transition;
import dev.coffeeclient.setting.Labeled;
import dev.coffeeclient.util.Colors;

import java.util.Locale;

/**
 * A complete theme: surface palette, effects, motion (screen transition, motion style, animation speed) and
 * window settings (blur, dim, opacity). Picking a preset writes its values into the theme settings, so
 * everything stays tweakable afterwards, and the theme remembers those tweaks. A preset without a palette
 * (Espresso, Custom) derives its surfaces from the accent hue instead.
 *
 * <p>Reduce motion and UI sounds are not set by presets: a theme visited for the first time keeps the
 * current values, and remembers its own from then on.
 */
public enum ThemePreset implements Labeled {
	ESPRESSO(null, look(0xFFE7C88B, 0xFFC08552, AccentMode.SHIMMER, 0xFFDDE0E6)
		.corners(Corners.NOTCHED).shadow(Shadow.SOFT).texture(Texture.GRAIN, 0.35).glow(0.25).backdrop(Backdrop.Scene.STEAM).motion(Transition.POUR, MotionStyle.SMOOTH, 1.0).window(true, 1.0)),
	LATTE(new Palette(0xFFF4ECDF, 0xFFE6D8C2, 0xFFE0CFB2, 0xFFEDE1CD, 0xFFB08F68, 0x55F3E6D3, 0x88C9B396),
		look(0xFF9C5B2E, 0xFFC98B5B, AccentMode.GRADIENT, 0xFF3B2A1E)
			.corners(Corners.ROUND).shadow(Shadow.SOFT).texture(Texture.PAPER, 0.4).gloss().backdrop(Backdrop.Scene.STEAM).opacity(0.95).motion(Transition.FADE, MotionStyle.SMOOTH, 1.0).window(true, 0.6)),
	MOCHA(new Palette(0xFF2C1F1B, 0xFF1C1311, 0xFF372520, 0xFF2E201C, 0xFF5C3F36, 0, 0),
		look(0xFFE08E9B, 0xFFC9785D, AccentMode.GRADIENT, 0xFFF1DDD3)
			.corners(Corners.ROUND).shadow(Shadow.SOFT).texture(Texture.GRAIN, 0.3).gloss().glow(0.2).backdrop(Backdrop.Scene.BEANS).motion(Transition.POUR, MotionStyle.SMOOTH, 1.0).window(true, 1.0)),
	ROAST(new Palette(0xFF171311, 0xFF0D0A09, 0xFF201916, 0xFF1A1513, 0xFF4A3A30, 0, 0),
		look(0xFFD9824B, 0xFF8C4A2F, AccentMode.STATIC, 0xFFEADBC8)
			.corners(Corners.SHARP).shadow(Shadow.DITHER).texture(Texture.CARBON, 0.6).backdrop(Backdrop.Scene.BEANS).motion(Transition.SLIDE, MotionStyle.SNAPPY, 1.25).window(true, 1.0).flat()),
	COLD_BREW(new Palette(0xFF131A24, 0xFF0A0E14, 0xFF19222E, 0xFF141C26, 0xFF2E4257, 0, 0),
		look(0xFF8FD3FF, 0xFF5B8CFF, AccentMode.SHIMMER, 0xFFDCE8F2)
			.corners(Corners.ROUND).shadow(Shadow.SOFT).texture(Texture.FROST, 0.55).glow(0.35).backdrop(Backdrop.Scene.SNOW).motion(Transition.ZOOM, MotionStyle.SMOOTH, 1.0).window(true, 0.9)),
	MATCHA(new Palette(0xFF19221B, 0xFF0F1510, 0xFF202B22, 0xFF1A231C, 0xFF3F5A44, 0, 0),
		look(0xFFA8D672, 0xFF6FBF73, AccentMode.GRADIENT, 0xFFE3EEDC)
			.corners(Corners.SOFT).shadow(Shadow.SOFT).texture(Texture.PAPER, 0.35).backdrop(Backdrop.Scene.PETALS).motion(Transition.SLIDE, MotionStyle.BOUNCY, 1.0).window(true, 0.9)),
	CARAMEL(new Palette(0xFF261B0F, 0xFF170F07, 0xFF302213, 0xFF281C0F, 0xFF6B4B22, 0, 0),
		look(0xFFF2A541, 0xFFFFD27A, AccentMode.SHIMMER, 0xFFF6E7CF)
			.corners(Corners.ROUND).shadow(Shadow.SOFT).texture(Texture.GRAIN, 0.25).gloss().glow(0.4).backdrop(Backdrop.Scene.EMBERS).motion(Transition.ZOOM, MotionStyle.BOUNCY, 1.0).window(true, 1.0)),
	AERO_GLASS(new Palette(0xFF2C6E92, 0xFF123A55, 0xFF3A86AD, 0xFF1F5374, 0xFF8FD8F5, 0x5518A0D8, 0x8810405F),
		look(0xFF7CF0B0, 0xFF59C3FF, AccentMode.GRADIENT, 0xFFF2FBFF)
			.corners(Corners.SOFT).shadow(Shadow.SOFT).texture(Texture.NONE, 0).gloss().glow(0.3).backdrop(Backdrop.Scene.BUBBLES).opacity(0.8).motion(Transition.FADE, MotionStyle.SMOOTH, 1.0).window(true, 0.5)),
	VAPORWAVE(new Palette(0xFF261438, 0xFF140A22, 0xFF331A4A, 0xFF2A153D, 0xFFB04BD8, 0x55B967FF, 0xAA1A0533),
		look(0xFFFF71CE, 0xFF01CDFE, AccentMode.GRADIENT, 0xFFFBE6FF)
			.corners(Corners.NOTCHED).shadow(Shadow.DITHER).texture(Texture.SCANLINES, 0.35).glow(0.6).backdrop(Backdrop.Scene.GRID).motion(Transition.GLITCH, MotionStyle.SNAPPY, 1.25).window(false, 0.8)),
	TERMINAL(new Palette(0xFF060B06, 0xFF020502, 0xFF0A140A, 0xFF081008, 0xFF1F6B2A, 0x66001400, 0xAA000800),
		look(0xFF39FF6A, 0xFF1FAF3F, AccentMode.PULSE, 0xFF7DFF8A)
			.corners(Corners.SHARP).shadow(Shadow.NONE).texture(Texture.SCANLINES, 0.7).glow(0.4).backdrop(Backdrop.Scene.RAIN).font(TextFont.TERMINAL).motion(Transition.CRT, MotionStyle.SNAPPY, 1.5).window(false, 1.0).flat()),
	CATPPUCCIN(new Palette(0xFF1E1E2E, 0xFF181825, 0xFF313244, 0xFF262637, 0xFF45475A, 0, 0),
		look(0xFFCBA6F7, 0xFF89B4FA, AccentMode.SHIMMER, 0xFFCDD6F4)
			.corners(Corners.ROUND).shadow(Shadow.SOFT).texture(Texture.NONE, 0).backdrop(Backdrop.Scene.SNOW).motion(Transition.SLIDE, MotionStyle.SMOOTH, 1.0).window(true, 1.0)),
	GRUVBOX(new Palette(0xFF282828, 0xFF1D2021, 0xFF3C3836, 0xFF32302F, 0xFF665C54, 0, 0),
		look(0xFFFABD2F, 0xFFFE8019, AccentMode.GRADIENT, 0xFFEBDBB2)
			.corners(Corners.SHARP).shadow(Shadow.DITHER).texture(Texture.GRAIN, 0.3).backdrop(Backdrop.Scene.EMBERS).motion(Transition.SLIDE, MotionStyle.SNAPPY, 1.0).window(true, 1.0).flat()),
	/** Pure black with a hot-pink glow: a dark pink-tinted border and no gradient, so the glow does the work. */
	XOXO(new Palette(0xFF0C080B, 0xFF040203, 0xFF17101A, 0xFF100A12, 0xFF4A1A3A, 0, 0),
		look(0xFFFF2E93, 0xFFFF7AC0, AccentMode.STATIC, 0xFFFFE4F1)
			.corners(Corners.SOFT).shadow(Shadow.SOFT).texture(Texture.NONE, 0).glow(0.85).backdrop(Backdrop.Scene.NONE).motion(Transition.FADE, MotionStyle.SMOOTH, 1.0).window(true, 1.0)),
	SAKURA(new Palette(0xFF2B1A23, 0xFF1C1117, 0xFF37212C, 0xFF2E1C25, 0xFF8A4F67, 0, 0),
		look(0xFFFFA7C4, 0xFFFFD1DC, AccentMode.SHIMMER, 0xFFFFE8F0)
			.corners(Corners.SOFT).shadow(Shadow.SOFT).texture(Texture.GRAIN, 0.2).gloss().glow(0.25).backdrop(Backdrop.Scene.PETALS).motion(Transition.FADE, MotionStyle.ELASTIC, 0.75).window(true, 0.9)),
	NEON_NIGHT(new Palette(0xFF0C0C12, 0xFF050508, 0xFF13131C, 0xFF0F0F16, 0xFF2A2A3A, 0, 0),
		look(0xFF00F0FF, 0xFFFF00C8, AccentMode.CHROMA, 0xFFE8E8FF)
			.corners(Corners.NOTCHED).shadow(Shadow.NONE).texture(Texture.HEX, 0.4).glow(1.0).backdrop(Backdrop.Scene.AURORA).motion(Transition.GLITCH, MotionStyle.SNAPPY, 1.25).window(false, 0.9)),
	/** Black to deep red: blood-red accent over a dark, slightly misty backdrop. */
	VAMPIRE(new Palette(0xFF1A0508, 0xFF0A0203, 0xFF2A0A10, 0xFF200810, 0xFF5C1520, 0, 0),
		look(0xFFE01B3C, 0xFF8B0A1F, AccentMode.GRADIENT, 0xFFF5E6E8)
			.corners(Corners.SOFT).shadow(Shadow.SOFT).texture(Texture.GRAIN, 0.15).gloss().glow(0.35).backdrop(Backdrop.Scene.EMBERS).motion(Transition.FADE, MotionStyle.SMOOTH, 0.75).window(true, 1.0)),
	/**
	 * OLED black liquid glass: frosted near-black panels with light rims over a slow dark-silk backdrop, pill
	 * controls and a neon green glow on whatever is switched on.
	 */
	NITRO(new Palette(0xFF151816, 0xFF080A09, 0xFF1C201D, 0xFF1A1E1B, 0xFF363D38, 0xB8000000, 0xE8000000),
		look(0xFF5CF07A, 0xFF2EE6C0, AccentMode.STATIC, 0xFFE8EDE9)
			.style(Style.GLASS).corners(Corners.SOFT).shadow(Shadow.SOFT).texture(Texture.NONE, 0).glow(0.6).backdrop(Backdrop.Scene.SILK)
			.opacity(0.75).motion(Transition.GLIDE, MotionStyle.SMOOTH, 1.0).window(true, 1.0).flat()),
	/** Keeps whatever is set; surfaces follow the accent hue. */
	CUSTOM(null, null);

	/**
	 * Surface colours. {@code screenTop}/{@code screenBottom} tint the screen behind windows (0 = derive
	 * from the panel colour).
	 */
	public record Palette(int panelTop, int panelBottom, int header, int control, int border, int screenTop, int screenBottom) {
	}

	/** The effect settings a preset writes. */
	public static final class Look {
		final int accent, accent2, text;
		final AccentMode mode;
		Style style = Style.CLASSIC;
		Corners corners = Corners.SHARP;
		Shadow shadow = Shadow.NONE;
		Texture texture = Texture.NONE;
		double textureStrength;
		double glow;
		boolean gloss;
		Backdrop.Scene backdrop = Backdrop.Scene.NONE;
		TextFont font = TextFont.MINECRAFT;
		double opacity = 0.9;
		double accentSpeed = 1.0;
		double backdropStrength = 0.7;
		double vignette = 0.4;
		Theme.Transition transition = Theme.Transition.POUR;
		Theme.MotionStyle motionStyle = Theme.MotionStyle.SMOOTH;
		double animationSpeed = 1.0;
		boolean blur = true;
		double backgroundDim = 1.0;
		boolean gleam = true, sheen = true;

		Look(int accent, int accent2, AccentMode mode, int text) {
			this.accent = accent;
			this.accent2 = accent2;
			this.mode = mode;
			this.text = text;
		}

		Look style(Style s) { style = s; return this; }
		Look corners(Corners c) { corners = c; return this; }
		Look shadow(Shadow s) { shadow = s; return this; }
		Look texture(Texture t, double strength) { texture = t; textureStrength = strength; return this; }
		Look glow(double g) { glow = g; return this; }
		Look gloss() { gloss = true; return this; }
		Look backdrop(Backdrop.Scene s) { backdrop = s; return this; }
		Look font(TextFont f) { font = f; return this; }
		Look opacity(double o) { opacity = o; return this; }
		Look motion(Theme.Transition t, Theme.MotionStyle m, double speed) { transition = t; motionStyle = m; animationSpeed = speed; return this; }
		Look window(boolean blur, double dim) { this.blur = blur; backgroundDim = dim; return this; }
		/** No gradients on headers and no sheen: flat, hard-edged surfaces. */
		Look flat() { gleam = false; sheen = false; return this; }
	}

	private static Look look(int accent, int accent2, AccentMode mode, int text) {
		return new Look(accent, accent2, mode, text);
	}

	public final Palette palette;
	final Look look;

	ThemePreset(Palette palette, Look look) {
		this.palette = palette;
		this.look = look;
	}

	/** The name shown in the picker and settings: words capitalised, except XOXO. */
	@Override
	public String label() {
		if (this == XOXO) return "XOXO";
		String n = name().toLowerCase(Locale.ROOT).replace('_', ' ');
		StringBuilder b = new StringBuilder();
		for (String word : n.split(" ")) b.append(b.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		return b.toString();
	}

	/** Whether the preset draws interfaces in the Glass style. */
	public boolean glass() {
		return look != null && look.style == Style.GLASS;
	}

	/** Accent shown on the preset's swatch. */
	public int swatchAccent() {
		return this == CUSTOM ? 0xFFFFFFFF : look == null ? 0xFFE7C88B : look.accent;
	}

	public int swatchAccent2() {
		return this == CUSTOM ? 0xFF9A9A9A : look == null ? 0xFFC08552 : look.accent2;
	}

	/** The preset's window fill: its palette panel colour, or one derived from the accent. */
	private int windowBackground() {
		if (palette != null) return palette.panelTop();
		float[] hsv = Colors.toHsv(look.accent);
		return Colors.fromHsv(hsv[0], hsv[1] * 0.95f, 0.098f, 255);
	}

	/** Write this preset's look into {@code t}'s settings. Custom changes nothing. */
	void applyTo(Theme t) {
		if (look == null) return;
		t.accentColor.set(look.accent);
		t.accent2Color.set(look.accent2);
		t.windowBackgroundColor.set(windowBackground());
		t.accentMode.set(look.mode);
		t.textColor.set(look.text);
		t.textFont.set(look.font);
		t.style.set(look.style);
		t.corners.set(look.corners);
		t.shadow.set(look.shadow);
		t.texture.set(look.texture);
		t.textureStrength.set(look.textureStrength);
		t.glow.set(look.glow);
		t.gloss.set(look.gloss);
		t.backdrop.set(look.backdrop);
		t.opacity.set(look.opacity);
		t.accentSpeed.set(look.accentSpeed);
		t.backdropStrength.set(look.backdropStrength);
		t.vignette.set(look.vignette);
		t.gleam.set(look.gleam);
		t.sheen.set(look.sheen);
		t.transition.set(look.transition);
		t.motionStyle.set(look.motionStyle);
		t.animationSpeed.set(look.animationSpeed);
		t.blur.set(look.blur);
		t.backgroundDim.set(look.backgroundDim);
	}

	/** Whether {@code t}'s live look is exactly this preset's (no unsaved tweaks). */
	boolean matches(Theme t) {
		if (look == null) return true;
		return t.accentColor.argb() == look.accent && t.accent2Color.argb() == look.accent2
			&& t.windowBackgroundColor.argb() == windowBackground() && t.accentMode.get() == look.mode
			&& t.textColor.argb() == look.text && t.textFont.get() == look.font && t.style.get() == look.style && t.corners.get() == look.corners
			&& t.shadow.get() == look.shadow && t.texture.get() == look.texture && near(t.textureStrength.get(), look.textureStrength)
			&& near(t.glow.get(), look.glow) && t.gloss.get() == look.gloss && t.backdrop.get() == look.backdrop
			&& near(t.opacity.get(), look.opacity) && near(t.accentSpeed.get(), look.accentSpeed)
			&& near(t.backdropStrength.get(), look.backdropStrength) && t.gleam.get() == look.gleam && t.sheen.get() == look.sheen
			&& near(t.vignette.get(), look.vignette) && t.transition.get() == look.transition
			&& t.motionStyle.get() == look.motionStyle && near(t.animationSpeed.get(), look.animationSpeed)
			&& t.blur.get() == look.blur && near(t.backgroundDim.get(), look.backgroundDim);
	}

	private static boolean near(double a, double b) {
		return Math.abs(a - b) < 1e-4;
	}
}
