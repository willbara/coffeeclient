package dev.coffeeclient.gui.theme;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.coffeeclient.gui.anim.Ease;
import dev.coffeeclient.gui.anim.Motion;
import dev.coffeeclient.gui.fx.Backdrop;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.Labeled;
import dev.coffeeclient.setting.StringSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.Colors;

import java.util.List;
import java.util.Locale;

/**
 * The single source of UI colour and style. Every widget reads its colours from here at draw time, so a
 * change shows on the next frame.
 *
 * <p>A {@link ThemePreset} sets the surface palette and writes a matching look (accent, effects,
 * backdrop), motion (transition, motion style, animation speed) and window settings into the settings
 * below, which stay editable. Every theme remembers its own settings: leaving a theme stores all of
 * {@link #themeSettings()} under its name, and picking it again restores them. Presets without a palette
 * derive the surfaces from the accent hue. {@link #accent} is refreshed every frame from the accent mode,
 * so pulsing or rainbow accents reach every widget; {@link #accentAt} samples it along a surface for
 * flowing effects.
 */
public final class Theme {
	/** Interface fonts: vanilla, the TrueType fonts (smoothly filtered) and two pixel fonts. */
	public enum TextFont implements Labeled {
		MINECRAFT("Minecraft", null), JETBRAINS_MONO("JetBrains Mono", "jetbrains_mono"), COMFORTAA("Comfortaa", "comfortaa"),
		TW_CEN_MT("Tw Cen MT", "tw_cen_mt"), OPEN_SANS("Open Sans", "open_sans"), PIXELATION("Pixelation", "pixelation"),
		TERMINAL("Terminal", "terminal");

		private final String label;
		/** Font id under the coffeeclient namespace, or null for the vanilla font. */
		public final String id;

		TextFont(String label, String id) {
			this.label = label;
			this.id = id;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public enum AccentMode { STATIC, GRADIENT, PULSE, SHIMMER, RAINBOW, CHROMA, REACTIVE }

	/** Panel corner shape; the radius is the number of stepped pixels cut from each corner. */
	public enum Corners {
		SHARP(new int[0]), NOTCHED(new int[] {1}), ROUND(new int[] {2, 1}), SOFT(new int[] {4, 2, 1, 1});

		public final int[] insets;

		Corners(int[] insets) {
			this.insets = insets;
		}
	}

	public enum Shadow { NONE, SOFT, DITHER }

	public enum Texture { NONE, GRAIN, SCANLINES, DITHER, PAPER, CARBON, HEX, FROST }

	public enum MotionStyle {
		SMOOTH(Ease.OUT_CUBIC), SNAPPY(Ease.OUT_EXPO), BOUNCY(Ease.OUT_BACK), ELASTIC(Ease.OUT_ELASTIC);

		public final Ease ease;

		MotionStyle(Ease ease) {
			this.ease = ease;
		}
	}

	public enum Transition { NONE, FADE, ZOOM, SLIDE, POUR, GLITCH, CRT, GLIDE }

	/**
	 * How interfaces are drawn. Classic: pixel frames with stepped corners. Glass: smooth rounded, frosted
	 * panels with light rims and soft shadows, pill controls and switch toggles.
	 */
	public enum Style { CLASSIC, GLASS }

	public static final int DEFAULT_ACCENT = 0xFFE7C88B;
	/** Accents saved by older versions: the R/G/B sliders and the earlier gold default. */
	private static final String[] LEGACY_CHANNELS = {"Red (R)", "Green (G)", "Blue (B)"};
	private static final int OLD_DEFAULT = 0xFFE5B23C;
	private static final int WARNING = 0xFFFF4A3D;

	public final EnumSetting<ThemePreset> preset = new EnumSetting<>("Theme preset", "A complete look; pick one, then tweak anything below", ThemePreset.ESPRESSO)
		.group("Theme");
	public final ColorSetting accentColor = new ColorSetting("Accent color", "Main accent colour", DEFAULT_ACCENT).group("Accent");
	public final ColorSetting accent2Color = new ColorSetting("Accent color 2", "Second accent, used by gradient, pulse and chroma modes", 0xFFC08552)
		.group("Accent");
	/** Fill colour of client windows and panels (the preset's panel colour by default). */
	public final ColorSetting windowBackgroundColor = new ColorSetting("Window background color", "Fill colour of client windows and panels", 0xFF191610)
		.group("Window");
	public final EnumSetting<AccentMode> accentMode = new EnumSetting<>("Accent mode", "How the accent moves: static, gradient, pulse, shimmer, rainbow, chroma or reacting to damage", AccentMode.SHIMMER)
		.group("Accent");
	public final NumberSetting accentSpeed = new NumberSetting("Accent speed", "Speed of moving accents", 1.0, 0.1, 3.0, 0.1)
		.format(v -> String.format(Locale.ROOT, "%.1fx", v)).group("Accent");
	/** Colour of all interface text (headings a touch brighter, secondary text dimmer). */
	public final ColorSetting textColor = new ColorSetting("Text color", "Colour of all interface text", 0xFFDDE0E6).group("Theme");
	public final EnumSetting<TextFont> textFont = new EnumSetting<>("Interface font", "Font used by client interfaces", TextFont.MINECRAFT).group("Theme");
	/**
	 * The single Custom theme slot of older versions (read only, as a fallback for {@link #themeStates}).
	 * Not shown as a control.
	 */
	public final StringSetting customTheme = new StringSetting("Custom theme", "Saved custom theme", "", 8192);
	/**
	 * What each theme remembers, as a JSON object from preset name to that theme's settings (Custom also
	 * holds the preset whose surfaces it sits on). Kept apart from the live settings so switching themes
	 * never loses tweaks. Not shown as a control.
	 */
	public final StringSetting themeStates = new StringSetting("Theme states", "Settings remembered per theme", "", 65536);

	public final EnumSetting<Style> style = new EnumSetting<>("Interface style", "Classic: pixel frames. Glass: rounded frosted panels, pill controls and soft glows", Style.CLASSIC)
		.group("Effects");
	public final EnumSetting<Corners> corners = new EnumSetting<>("Corners", "Panel corner shape (Classic style)", Corners.NOTCHED).group("Effects");
	public final BooleanSetting gloss = new BooleanSetting("Gloss", "Glassy highlight across the top of panels", false).group("Effects");
	public final NumberSetting glow = new NumberSetting("Glow", "Accent glow around windows (0% = none)", 0.25, 0.0, 1.0, 0.05)
		.format(v -> Math.round(v * 100) + "%").group("Effects");
	public final EnumSetting<Shadow> shadow = new EnumSetting<>("Shadow", "Drop shadow under windows", Shadow.SOFT).group("Effects");
	public final EnumSetting<Texture> texture = new EnumSetting<>("Texture", "Surface texture on panels", Texture.GRAIN).group("Effects");
	public final NumberSetting textureStrength = new NumberSetting("Texture strength", "How visible the panel texture is", 0.35, 0.0, 1.0, 0.05)
		.format(v -> Math.round(v * 100) + "%").group("Effects");
	public final NumberSetting vignette = new NumberSetting("Vignette", "Darkened screen edges behind client screens", 0.4, 0.0, 1.0, 0.05)
		.format(v -> Math.round(v * 100) + "%").group("Effects");
	public final EnumSetting<Backdrop.Scene> backdrop = new EnumSetting<>("Backdrop", "Animated scene behind client screens", Backdrop.Scene.STEAM).group("Effects");
	public final NumberSetting backdropStrength = new NumberSetting("Backdrop strength", "Visibility of the animated backdrop", 0.7, 0.0, 1.0, 0.05)
		.format(v -> Math.round(v * 100) + "%").group("Effects");
	public final BooleanSetting gleam = new BooleanSetting("Gleam", "Gradients on headers and enabled rows", true).group("Effects");
	public final BooleanSetting sheen = new BooleanSetting("Sheen", "Slow diagonal shimmer across headers", true).group("Effects");

	public final EnumSetting<Transition> transition = new EnumSetting<>("Screen transition", "How client screens open and close", Transition.POUR).group("Motion");
	public final EnumSetting<MotionStyle> motionStyle = new EnumSetting<>("Motion style", "Feel of pops and transitions", MotionStyle.SMOOTH).group("Motion");
	public final NumberSetting animationSpeed = new NumberSetting("Animation speed", "Hover/expand transition speed", 1.0, 0.25, 3.0, 0.25)
		.format(v -> String.format(Locale.ROOT, "%.2fx", v)).group("Motion");
	public final BooleanSetting reduceMotion = new BooleanSetting("Reduce motion", "Turn off transitions, backdrops, staggering and shakes", false).group("Motion");
	public final BooleanSetting uiSounds = new BooleanSetting("UI sounds", "Soft clicks when toggling and opening screens", false).group("Motion");

	/** Multiplies the alpha of every panel/control fill (text stays opaque). */
	public final NumberSetting opacity = new NumberSetting("Interface opacity", "Opacity of panels and controls (100% = solid)", 0.9, 0.2, 1.0, 0.05)
		.format(v -> Math.round(v * 100) + "%").group("Window");
	public final BooleanSetting blur = new BooleanSetting("Blur background", "Blur the world behind client screens", true).group("Window");
	/** Darkness of the screen behind client windows (0% = none). */
	public final NumberSetting backgroundDim = new NumberSetting("Background dim", "Dimming behind client screens (0% = none)", 1.0, 0.0, 1.0, 0.05)
		.format(v -> Math.round(v * 100) + "%").group("Window");

	/** Screen dim behind windows. */
	public int background;
	/** Window / card fill (flat). */
	public int panel;
	/** Window fill gradient: top and bottom. */
	public int panelTop;
	public int panelBottom;
	/** Bottom of the screen dim (top is {@link #background}). */
	public int backgroundBottom;
	/** Slightly lifted fill for headers and hovered rows. */
	public int panelHeader;
	/** 1px frame. */
	public int border;
	/** Accent bar, highlights, progress fill. Animated by the accent mode (refreshed each frame). */
	public int accent;
	/** The accent as set, before the accent mode moves it. */
	public int baseAccent;
	public int accent2;
	public int text;
	/** Headings and emphasised values. */
	public int heading;
	public int dimText;
	public int error = 0xFFE0584F;
	public int warn = 0xFFF0B030;
	/** Fill of a control (button/field) body. */
	public int control;
	public int controlHover;
	/** Light surfaces (dark text): text is drawn without a drop shadow. */
	public boolean light;

	/** 0..1 "danger" level fed by ReactiveFx (recent damage, low health); drives the Reactive accent. */
	private float reactive;
	/** Preset whose surfaces the Custom theme uses (null: derived from the accent). */
	private ThemePreset customBase;
	/** The theme in use before a change (a fresh Custom theme starts from it). */
	private ThemePreset lastPreset = ThemePreset.ESPRESSO;
	/** True while a config is being read or reset: the live settings are not the outgoing theme's yet. */
	private boolean loading;
	private float accentHue, accentSat, accentVal;

	public Theme() {
		preset.onChange(p -> {
			if (!loading) leaveTheme(lastPreset);
			enterTheme(p);
			lastPreset = p;
			recompute();
		});
		for (Setting<?> s : List.of(accentColor, accent2Color, windowBackgroundColor, textColor, opacity, backgroundDim)) s.onChange(v -> recompute());
		recompute();
	}

	/** Everything saved in the config: the shown settings plus the remembered themes (loaded first). */
	public List<Setting<?>> persistedSettings() {
		List<Setting<?>> all = new java.util.ArrayList<>();
		all.add(customTheme);
		all.add(themeStates);
		all.addAll(allSettings());
		return all;
	}

	/** Tell the theme a config is being read or reset, so switching presets then does not store anything. */
	public void loading(boolean on) {
		loading = on;
	}

	/** Every setting that belongs to a theme: look, motion and window. Presets set them, themes remember them. */
	public List<Setting<?>> themeSettings() {
		return List.of(accentColor, accent2Color, windowBackgroundColor, accentMode, accentSpeed, textColor, textFont,
			style, corners, gloss, glow, shadow, texture, textureStrength, vignette, backdrop, backdropStrength, gleam, sheen,
			opacity, transition, motionStyle, animationSpeed, reduceMotion, uiSounds, blur, backgroundDim);
	}

	// ---- per-theme state ----

	private JsonObject states() {
		try {
			JsonElement e = com.google.gson.JsonParser.parseString(themeStates.get());
			if (e.isJsonObject()) return e.getAsJsonObject();
		} catch (RuntimeException ex) {
			// unreadable: start from nothing
		}
		return new JsonObject();
	}

	/** What {@code p} remembers (for Custom, falling back to an older single-slot save), or null. */
	private JsonObject snapshotFor(ThemePreset p) {
		JsonElement e = states().get(p.name());
		if (e != null && e.isJsonObject()) return e.getAsJsonObject();
		return p == ThemePreset.CUSTOM ? legacyCustom() : null;
	}

	private JsonObject legacyCustom() {
		String raw = customTheme.get();
		if (raw == null || raw.isBlank()) return null;
		try {
			JsonElement e = com.google.gson.JsonParser.parseString(raw);
			return e.isJsonObject() ? e.getAsJsonObject() : null;
		} catch (RuntimeException ex) {
			return null;
		}
	}

	/** The live theme settings, plus the preset a Custom theme sits on. */
	private JsonObject snapshot(ThemePreset base) {
		JsonObject snap = new JsonObject();
		if (base != null) snap.addProperty("Base", base.name());
		for (Setting<?> s : themeSettings()) snap.add(s.name(), s.toJson());
		return snap;
	}

	private void applySnapshot(JsonObject snap) {
		for (Setting<?> s : themeSettings()) {
			JsonElement v = snap.get(s.name());
			if (v != null) s.fromJson(v);
		}
	}

	private void remember(ThemePreset p, JsonObject snap) {
		JsonObject all = states();
		all.add(p.name(), snap);
		themeStates.set(all.toString());
	}

	/** Drop what {@code p} remembered, so a reset survives a restart instead of coming back on load. */
	private void forget(ThemePreset p) {
		JsonObject all = states();
		all.remove(p.name());
		themeStates.set(all.toString());
	}

	/** Store the live settings under {@code p}, which is being left. */
	private void leaveTheme(ThemePreset p) {
		remember(p, snapshot(p == ThemePreset.CUSTOM ? customBase : null));
	}

	/** Make {@code p} the live theme: its own settings first, then whatever it remembered on top. */
	private void enterTheme(ThemePreset p) {
		JsonObject snap = snapshotFor(p);
		if (p == ThemePreset.CUSTOM) {
			if (snap == null) {
				// a new Custom theme starts from the look in use
				customBase = lastPreset == ThemePreset.CUSTOM ? null : lastPreset;
				remember(ThemePreset.CUSTOM, snapshot(customBase));
				return;
			}
			customBase = baseFrom(snap);
		} else {
			p.applyTo(this);
		}
		if (snap != null) applySnapshot(snap);
		// Older Custom themes saved no window colour: take their base palette's fill, or derive one.
		if (p == ThemePreset.CUSTOM && snap != null && !snap.has(windowBackgroundColor.name())) {
			if (customBase != null && customBase.palette != null) windowBackgroundColor.set(customBase.palette.panelTop());
			else deriveWindowFromAccent();
		}
	}

	private void deriveWindowFromAccent() {
		float[] hsv = Colors.toHsv(Colors.withAlpha(accentColor.argb(), 255));
		windowBackgroundColor.set(Colors.fromHsv(hsv[0], hsv[1] * 0.95f, 0.098f, 255));
	}

	private static ThemePreset baseFrom(JsonObject snap) {
		try {
			JsonElement b = snap.get("Base");
			if (b == null || b.isJsonNull()) return null;
			ThemePreset p = ThemePreset.valueOf(b.getAsString());
			return p == ThemePreset.CUSTOM ? null : p;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Whether the live settings differ from the selected preset's own. Custom has nothing to differ from. */
	public boolean themeChanged() {
		ThemePreset p = preset.get();
		return p != ThemePreset.CUSTOM && !p.matches(this);
	}

	/** Keep the live settings as the Custom theme and switch to it; the preset they came from goes back to its own. */
	public void saveCustom() {
		ThemePreset from = preset.get();
		if (from == ThemePreset.CUSTOM) return;
		remember(ThemePreset.CUSTOM, snapshot(from));
		from.applyTo(this);
		preset.set(ThemePreset.CUSTOM);
	}

	/**
	 * Back to the theme's own settings: a preset's, or for Custom those of the preset it was made from.
	 * Reduce motion and UI sounds are left as they are (see {@link ThemePreset}).
	 */
	public void revertTheme() {
		ThemePreset p = preset.get();
		if (p != ThemePreset.CUSTOM) forget(p);
		(p == ThemePreset.CUSTOM ? (customBase == null ? ThemePreset.ESPRESSO : customBase) : p).applyTo(this);
		recompute();
	}

	public boolean hasCustomTheme() {
		return snapshotFor(ThemePreset.CUSTOM) != null;
	}

	public List<Setting<?>> allSettings() {
		// the preset comes first so loading a config applies it before the saved tweaks on top
		return List.of(preset, accentColor, accent2Color, windowBackgroundColor, accentMode, accentSpeed, textColor, textFont,
			style, corners, gloss, glow, shadow, texture, textureStrength, vignette, backdrop, backdropStrength, gleam, sheen,
			transition, motionStyle, animationSpeed, reduceMotion, uiSounds,
			opacity, blur, backgroundDim);
	}

	/** Back to the preset's colours (or the saved Custom theme). */
	public void resetColors() {
		revertTheme();
	}

	public void recompute() {
		baseAccent = Colors.withAlpha(accentColor.argb(), 255);
		accent2 = Colors.withAlpha(accent2Color.argb(), 255);
		float[] hsv = Colors.toHsv(baseAccent);
		accentHue = hsv[0];
		accentSat = hsv[1];
		accentVal = hsv[2];
		float a = opacity.floatValue();
		float dim = backgroundDim.floatValue();
		ThemePreset.Palette p = preset.get() == ThemePreset.CUSTOM ? (customBase == null ? null : customBase.palette) : preset.get().palette;
		int window = Colors.withAlpha(windowBackgroundColor.argb(), alpha(0xFF, a));
		panelTop = window;
		if (p == null) {
			float h = hsv[0], s = hsv[1], v = Math.max(hsv[2], 0.35f);
			float[] wh = Colors.toHsv(windowBackgroundColor.argb());
			// dark, warm surfaces (tinted by the chosen window colour) with cream text for strong contrast
			panelBottom = Colors.shade(panelTop, 0.6f);
			panelHeader = Colors.fromHsv(wh[0], wh[1], Math.min(1f, wh[2] * 1.28f), alpha(0xFF, Math.min(1f, a + 0.05f)));
			control = Colors.fromHsv(wh[0], wh[1], Math.min(1f, wh[2] * 1.07f), alpha(0xFF, Math.min(1f, a + 0.05f)));
			controlHover = Colors.fromHsv(wh[0], wh[1], Math.min(1f, wh[2] * 1.84f), alpha(0xFF, Math.min(1f, a + 0.05f)));
			background = Colors.fromHsv(h, s * 0.95f, 0.06f, Math.round(0x88 * dim));
			backgroundBottom = Colors.fromHsv(h, s * 0.95f, 0.035f, Math.round(0xA8 * dim));
			border = Colors.fromHsv(h, s * 0.80f, v * 0.68f, alpha(0xFF, Math.max(a, 0.6f)));
		} else {
			// keep each preset's gradient strength while the chosen colour sets the fill
			float ratio = luminance(p.panelBottom()) / Math.max(0.01f, luminance(p.panelTop()));
			panelBottom = Colors.shade(panelTop, Math.max(0.35f, Math.min(1f, ratio)));
			panelHeader = Colors.withAlpha(p.header(), alpha(0xFF, Math.min(1f, a + 0.05f)));
			control = Colors.withAlpha(p.control(), alpha(0xFF, Math.min(1f, a + 0.05f)));
			border = Colors.withAlpha(p.border(), alpha(0xFF, Math.max(a, 0.6f)));
			boolean lightSurface = luminance(panelTop) > 0.5f;
			controlHover = Colors.withAlpha(Colors.lerp(p.control(), lightSurface ? 0xFF000000 : 0xFFFFFFFF, 0.08f), alpha(0xFF, Math.min(1f, a + 0.05f)));
			int top = p.screenTop() != 0 ? p.screenTop() : Colors.withAlpha(Colors.shade(p.panelBottom(), 0.8f), 0x88);
			int bottom = p.screenBottom() != 0 ? p.screenBottom() : Colors.withAlpha(Colors.shade(p.panelBottom(), 0.5f), 0xA8);
			background = Colors.withAlpha(top, Math.round(Colors.alpha(top) * dim));
			backgroundBottom = Colors.withAlpha(bottom, Math.round(Colors.alpha(bottom) * dim));
		}
		panel = Colors.lerp(panelTop, panelBottom, 0.55f);
		light = luminance(panelTop) > 0.5f;
		text = Colors.withAlpha(textColor.argb(), 255);
		if (light) {
			heading = Colors.lerp(text, 0xFF000000, 0.35f);
			dimText = Colors.lerp(text, Colors.withAlpha(panelBottom, 255), 0.4f);
		} else {
			heading = Colors.lerp(text, 0xFFFFFFFF, 0.35f);
			dimText = Colors.shade(text, 0.68f);
		}
		accent = accentAt(0f);
	}

	/** Called once per frame: moves {@link #accent} along with the accent mode and decays the reactive level. */
	public void frame(float dt) {
		reactive = Math.max(0f, reactive - dt * 0.8f);
		accent = accentAt(0f);
	}

	/** Raise the reactive level (0..1); it decays by itself. */
	public void react(float level) {
		reactive = Math.max(reactive, Math.min(1f, level));
	}

	public float reactiveLevel() {
		return reactive;
	}

	/**
	 * The accent at a position along a surface ({@code pos} 0..1, values outside wrap or keep flowing).
	 * Neighbouring surfaces pass neighbouring positions so moving accents flow across them as one.
	 */
	public int accentAt(float pos) {
		float t = Motion.time() * accentSpeed.floatValue();
		return switch (accentMode.get()) {
			case STATIC -> baseAccent;
			case GRADIENT -> Colors.lerp(baseAccent, accent2, triangle(pos * 0.8f - t * 0.25f));
			case PULSE -> Colors.lerp(baseAccent, accent2, Motion.wave(t, 2.2f, 0));
			case SHIMMER -> {
				float band = (float) Math.pow(Math.max(0, Math.cos((pos * 0.5f - t * 0.3f) * Math.PI * 2)), 10);
				yield Colors.lerp(baseAccent, Colors.lerp(baseAccent, 0xFFFFFFFF, 0.55f), band);
			}
			case RAINBOW -> Colors.fromHsv(fract(pos * 0.6f + t * 0.12f) * 360f, Math.max(0.45f, accentSat), Math.max(0.85f, accentVal), 255);
			case CHROMA -> {
				float[] hsv2 = Colors.toHsv(accent2);
				float k = triangle(pos * 0.7f - t * 0.3f);
				float h = lerpHue(accentHue, hsv2[0], k);
				yield Colors.fromHsv(h, Math.max(0.75f, accentSat), 1f, 255);
			}
			case REACTIVE -> Colors.lerp(baseAccent, WARNING, reactive);
		};
	}

	/** {@link #accentAt} with an alpha. */
	public int accentAt(float pos, int alpha) {
		return Colors.withAlpha(accentAt(pos), alpha);
	}

	/**
	 * The light accent (Shimmer's highlight), held still: for text that stays put, like the "[Coffee]" chat
	 * tag, where the moving {@link #accent} would freeze each line at a different shade.
	 */
	public int lightAccent() {
		return Colors.lerp(baseAccent, 0xFFFFFFFF, 0.55f);
	}

	/** Accent at a given alpha, for translucent highlights. */
	public int accent(int alpha) {
		return Colors.withAlpha(accent, alpha);
	}

	/** Brighter text used for headings and headline numbers. */
	public int strongText() {
		return heading;
	}

	public int cornerRadius() {
		return corners.get().insets.length;
	}

	/** True when interfaces use the Glass style. */
	public boolean glass() {
		return style.get() == Style.GLASS;
	}

	/** True while effects that move on their own (backdrops, staggers, shakes) should run. */
	public boolean motion() {
		return !reduceMotion.get();
	}

	/**
	 * Older configs: the accent was three R/G/B number settings, and an earlier default was gold. Saved
	 * channels become the accent colour; the old gold default follows the new default. A saved Stars
	 * backdrop (removed) becomes Snow.
	 */
	public void migrateLegacy(JsonObject themeJson) {
		if (themeJson != null && themeJson.get(backdrop.name()) instanceof com.google.gson.JsonPrimitive saved
			&& saved.getAsString().equals("STARS")) backdrop.set(Backdrop.Scene.SNOW);
		if (themeJson == null || themeJson.has(accentColor.name())) return;
		int[] rgb = new int[3];
		for (int i = 0; i < 3; i++) {
			JsonElement e = themeJson.get(LEGACY_CHANNELS[i]);
			if (e == null) return;
			try {
				rgb[i] = Math.max(0, Math.min(255, (int) Math.round(e.getAsDouble())));
			} catch (RuntimeException ex) {
				return;
			}
		}
		int legacy = Colors.rgb(rgb[0], rgb[1], rgb[2]);
		applyClassic((legacy & 0xFFFFFF) == (OLD_DEFAULT & 0xFFFFFF) ? DEFAULT_ACCENT : legacy);
	}

	/**
	 * The look from before presets existed (accent-derived surfaces, no effects), saved as the Custom
	 * theme and selected. Text colour, font and the window settings are left as they are.
	 */
	public void applyClassic(int accentArgb) {
		accentColor.set(accentArgb);
		accentMode.set(AccentMode.STATIC);
		style.set(Style.CLASSIC);
		corners.set(Corners.SHARP);
		gloss.set(false);
		glow.set(0.0);
		shadow.set(Shadow.NONE);
		texture.set(Texture.NONE);
		vignette.set(0.0);
		backdrop.set(Backdrop.Scene.NONE);
		deriveWindowFromAccent();
		remember(ThemePreset.CUSTOM, snapshot(null));
		customBase = null;
		if (preset.get() == ThemePreset.CUSTOM) recompute();
		else preset.set(ThemePreset.CUSTOM);
	}

	private static float luminance(int c) {
		return (0.2126f * Colors.red(c) + 0.7152f * Colors.green(c) + 0.0722f * Colors.blue(c)) / 255f;
	}

	private static float fract(float v) {
		return v - (float) Math.floor(v);
	}

	/** 0→1→0 over each unit. */
	private static float triangle(float v) {
		float f = fract(v);
		return f < 0.5f ? f * 2 : 2 - f * 2;
	}

	private static float lerpHue(float a, float b, float t) {
		float d = ((b - a + 540f) % 360f) - 180f;
		return (a + d * t + 360f) % 360f;
	}

	private static int alpha(int base, float factor) {
		return Math.round(base * factor);
	}
}
