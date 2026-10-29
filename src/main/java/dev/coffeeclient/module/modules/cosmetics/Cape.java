package dev.coffeeclient.module.modules.cosmetics;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.anim.Motion;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.client.PlayerProtect;
import dev.coffeeclient.setting.ActionSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.StringSetting;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.Open;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * A client-side cape (also shown on elytra when you have no elytra texture). Built-in capes are painted
 * in code from the theme colours, some animated; a custom PNG can be dropped in
 * {@code config/coffeeclient/capes/}. Built-in capes also paint matching elytra wings, shaped and
 * feather-shaded like the vanilla elytra texture (so resource packs that reshape it are followed).
 * The elytra can instead keep its own texture or use a PNG of its own.
 */
public final class Cape extends CosmeticModule {
	public enum Style { COFFEE, GRADIENT, SHIMMER, CUSTOM, VANILLA }
	public enum ElytraChoice { FOLLOW_CAPE, VANILLA, CUSTOM }

	/** A skin built from a base, and the source skin it was built from (so a late-loading source rebuilds it). */
	private record Built(PlayerSkin source, PlayerSkin skin) {
	}

	public final EnumSetting<Style> style = setting(new EnumSetting<>("Style", "Built-in cape design, Custom for your own PNG, or Vanilla for your real cape", Style.COFFEE));
	public final StringSetting file = setting(new StringSetting("File", "Name of a PNG in config/coffeeclient/capes (64x32 cape layout)", "cape", 64));
	public final EnumSetting<ElytraChoice> elytra = setting(new EnumSetting<>("Elytra", "Elytra texture: follow the cape, the default elytra, or your own PNG", ElytraChoice.FOLLOW_CAPE));
	public final StringSetting elytraFile = setting(new StringSetting("Elytra file", "Name of a PNG in config/coffeeclient/capes with the wings in the vanilla elytra spot", "elytra", 64));
	public final ActionSetting openFolder = setting(new ActionSetting("Open capes folder", "Open the folder custom capes are read from", this::openFolder));

	/** How often a missing custom PNG is looked for again, in ticks. */
	private static final int RETRY_TICKS = 20;
	private static final Identifier PAINTED = Identifier.fromNamespaceAndPath("coffeeclient", "cape/painted");
	private static final Identifier CUSTOM = Identifier.fromNamespaceAndPath("coffeeclient", "cape/custom");
	private static final Identifier CUSTOM_ELYTRA = Identifier.fromNamespaceAndPath("coffeeclient", "cape/custom_elytra");
	private static final Identifier VANILLA_WINGS = Identifier.withDefaultNamespace("textures/entity/equipment/wings/elytra.png");
	/** The default wings. Without an elytra texture the game shows the cape on the elytra, so this stands in for it. */
	private static final ClientAsset.Texture VANILLA_ELYTRA = new ClientAsset.ResourceTexture(VANILLA_WINGS, VANILLA_WINGS);
	/** The vanilla elytra texture: its alpha is the wing shape, its brightness the feather shading. Read once. */
	private static @Nullable NativeImage wings;
	private static boolean wingsRead;
	private @Nullable DynamicTexture painted;
	private @Nullable Identifier active;
	private String loadedFile = "";
	private @Nullable Identifier activeElytra;
	private String loadedElytra = "";
	private @Nullable ElytraChoice shownElytra;
	private @Nullable Style shownStyle;
	private Style paintedStyle;
	private int paintedAccent, paintedAccent2;
	private int ticks;
	private final Map<PlayerSkin, Built> skins = new HashMap<>();

	public Cape() {
		super("Cape", "A cape only you can see: built-in designs from your theme colours, or your own PNG.");
		file.visibleWhen(() -> style.get() == Style.CUSTOM);
		elytraFile.visibleWhen(() -> elytra.get() == ElytraChoice.CUSTOM);
		openFolder.visibleWhen(() -> style.get() == Style.CUSTOM || elytra.get() == ElytraChoice.CUSTOM);
	}

	private static Path folder() {
		return FabricLoader.getInstance().getConfigDir().resolve(CoffeeClient.MOD_ID).resolve("capes");
	}

	private void openFolder() {
		try {
			Files.createDirectories(folder());
			Open.path(folder());
		} catch (Exception e) {
			info("Could not open " + folder() + ": " + e.getMessage());
		}
	}

	/** The skin with this client's cape and elytra added, or {@code base} unchanged. Called from the skin lookup. */
	public static PlayerSkin apply(PlayerSkin base, String name) {
		Cape cape = ModuleManager.ifOn(Cape.class);
		if (cape == null || base == null || !cape.wornBy(name)) return base;
		PlayerSkin source = sourceSkin(base, name);
		Built built = cape.skins.get(base);
		if (built == null || !built.source().equals(source)) {
			built = new Built(source, build(cape, base, source));
			cape.skins.put(base, built);
		}
		return built.skin();
	}

	private static PlayerSkin build(Cape cape, PlayerSkin base, PlayerSkin source) {
		ClientAsset.Texture cloak = cloakOf(cape, base, source);
		// the elytra too: an account with its own elytra texture would otherwise keep the vanilla wings
		ClientAsset.Texture wings = switch (cape.elytra.get()) {
			case FOLLOW_CAPE -> (cape.active != null || cape.style.get() == Style.VANILLA) && cloak != null ? cloak : sourceWings(source);
			case VANILLA -> sourceWings(source);
			case CUSTOM -> cape.activeElytra == null ? sourceWings(source) : new ClientAsset.ResourceTexture(cape.activeElytra, cape.activeElytra);
		};
		return new PlayerSkin(base.body(), cloak, wings, base.model(), base.secure());
	}

	/** The back: your own cape in Vanilla style, else the Coffee one, else the cape the skin already has. */
	private static ClientAsset.@Nullable Texture cloakOf(Cape cape, PlayerSkin base, PlayerSkin source) {
		if (cape.style.get() == Style.VANILLA) return source.cape();
		return cape.active == null ? base.cape() : new ClientAsset.ResourceTexture(cape.active, cape.active);
	}

	/**
	 * The skin the elytra reads: your real account skin, unless PlayerProtect is swapping yours, in which
	 * case its skin stands (its cape included). Other players are not swapped, so their base skin is real.
	 */
	private static PlayerSkin sourceSkin(PlayerSkin base, String name) {
		var user = mc().getUser();
		if (user == null || !name.equals(user.getName())) return base;
		PlayerProtect protect = ModuleManager.ifOn(PlayerProtect.class);
		if (protect != null && protect.swapsOwnSkin()) return base;
		return mc().getSkinManager().createLookup(mc().getGameProfile(), false).get();
	}

	/** The skin's elytra, else its cape, else the default wings: what the game shows with no client change. */
	static ClientAsset.Texture sourceWings(PlayerSkin source) {
		if (source.elytra() != null) return source.elytra();
		return source.cape() != null ? source.cape() : VANILLA_ELYTRA;
	}

	@Override
	protected void onDisable() {
		active = null;
		activeElytra = null;
		skins.clear();
	}

	@Override
	public void onTick() {
		ticks++;
		sync();
		var theme = CoffeeClient.theme();
		Style s = style.get();
		if (s == Style.VANILLA) {
			active = null;
			return;
		}
		if (s == Style.CUSTOM) return;
		boolean animated = s == Style.SHIMMER || s == Style.COFFEE;
		boolean changed = painted == null || s != paintedStyle || theme.baseAccent != paintedAccent || theme.accent2 != paintedAccent2;
		if (changed || (animated && ticks % 2 == 0)) repaint(s);
	}

	private void repaint(Style s) {
		try {
			if (painted == null) {
				painted = new DynamicTexture(() -> "coffeeclient:cape", 64, 32, true);
				mc().getTextureManager().register(PAINTED, painted);
			}
			paintedStyle = s;
			paintedAccent = CoffeeClient.theme().baseAccent;
			paintedAccent2 = CoffeeClient.theme().accent2;
			paint(painted.getPixels(), s, paintedAccent, paintedAccent2, Motion.time());
			painted.upload();
			if (active != PAINTED) skins.clear();
			active = PAINTED;
		} catch (Throwable e) {
			CoffeeClient.LOG.warn("Cape texture failed: {}", e.toString());
			active = null;
		}
	}

	/**
	 * Drops the built skins when a setting they depend on changes, and loads the custom PNGs they use: when
	 * the file name changes, and once every {@link #RETRY_TICKS} while the file is missing (so a file dropped
	 * into the folder takes effect without changing the setting).
	 */
	private void sync() {
		ElytraChoice choice = elytra.get();
		Style s = style.get();
		if (choice != shownElytra || s != shownStyle) {
			shownElytra = choice;
			shownStyle = s;
			skins.clear();
		}
		boolean retry = ticks % RETRY_TICKS == 0;
		if (s == Style.CUSTOM && (!file.get().equals(loadedFile) || (active == null && retry))) {
			loadedFile = file.get();
			Identifier next = loadPng(loadedFile, CUSTOM);
			if (next != active) skins.clear();
			active = next;
		}
		if (choice == ElytraChoice.CUSTOM && (!elytraFile.get().equals(loadedElytra) || (activeElytra == null && retry))) {
			loadedElytra = elytraFile.get();
			Identifier next = loadPng(loadedElytra, CUSTOM_ELYTRA);
			if (next != activeElytra) skins.clear();
			activeElytra = next;
		}
	}

	/** Registers the capes-folder PNG called {@code name} under {@code id}; null if it is missing or unreadable. */
	private @Nullable Identifier loadPng(String name, Identifier id) {
		Path path = folder().resolve(name.endsWith(".png") ? name : name + ".png");
		if (!Files.isRegularFile(path)) return null;
		try (InputStream in = Files.newInputStream(path)) {
			NativeImage img = NativeImage.read(in);
			mc().getTextureManager().register(id, new DynamicTexture(() -> "coffeeclient:" + id.getPath(), img));
			return id;
		} catch (Exception e) {
			info("Could not read " + path.getFileName() + ": " + e.getMessage());
			return null;
		}
	}

	// ---- painting (64x32 cape layout: outer face at 1,1 10x16; inner at 12,1; elytra at 22,0) ----

	private static void paint(NativeImage img, Style s, int accent, int accent2, float time) {
		for (int y = 0; y < 32; y++) {
			for (int x = 0; x < 64; x++) {
				img.setPixel(x, y, base(s, x, y, accent, accent2, time));
			}
		}
		paintWings(img, s, accent, accent2, time);
		if (s == Style.COFFEE) {
			// accent trim around the outer face, and a cup on it
			int trim = Colors.withAlpha(accent, 255);
			for (int y = 1; y < 17; y++) {
				img.setPixel(1, y, trim);
				img.setPixel(10, y, trim);
			}
			for (int x = 1; x < 11; x++) {
				img.setPixel(x, 1, trim);
				img.setPixel(x, 16, trim);
			}
			int cream = 0xFFF1E4CC;
			String[] cup = {
				"......",
				".####.#",
				".#cc#.#",
				".#cc###",
				".####..",
				"..##...",
			};
			for (int r = 0; r < cup.length; r++) {
				for (int c = 0; c < cup[r].length(); c++) {
					char ch = cup[r].charAt(c);
					if (ch == '#') img.setPixel(3 + c, 7 + r, cream);
					else if (ch == 'c') img.setPixel(3 + c, 7 + r, trim);
				}
			}
			// steam: three wisps, each pixel rising from the cup and fading as it goes
			for (int w = 0; w < 3; w++) {
				for (int k = 0; k < 2; k++) {
					float f = (time * 0.7f + w * 0.37f + k * 0.5f) % 1f;
					int sy = 7 - Math.round(f * 5);
					int sx = 4 + w + (Math.sin((f * 2 + w) * Math.PI) > 0.4 ? 1 : 0);
					int alpha = Math.round(230 * (1 - f) * Math.min(1f, f * 4 + 0.3f));
					if (sy >= 2 && alpha > 20) blend(img, sx, sy, Colors.withAlpha(cream, alpha));
				}
			}
		}
	}

	// ---- elytra wings (the 22..46 x 0..22 corner of the cape texture) ----

	private static @Nullable NativeImage wings() {
		if (!wingsRead) {
			wingsRead = true;
			try (InputStream in = net.minecraft.client.Minecraft.getInstance().getResourceManager().open(VANILLA_WINGS)) {
				wings = NativeImage.read(in);
			} catch (Exception e) {
				CoffeeClient.LOG.warn("Cape: could not read the vanilla elytra texture: {}", e.toString());
			}
		}
		return wings;
	}

	/** The latte tulip on each Coffee wing, from y = 3, 9 texels wide around the wing's slanted middle. */
	private static final String[] TULIP = {
		"..##.##..", ".#######.", ".#######.", "..#####..", "...###...", "....#....", ".........",
		"#.......#", "##.....##", ".#######.", "..#####..", "...###...", "....#....",
		".#.....#.", ".##...##.", "..#####..", "...###...", "....#....", "....#...."
	};

	/**
	 * Wings in the cape's colours: the vanilla wing shape and feather shading, recoloured. Coffee is a
	 * latte: espresso with a crema rim and a milk-foam tulip poured on it; the others are theme gradients.
	 */
	private static void paintWings(NativeImage img, Style s, int accent, int accent2, float time) {
		NativeImage mask = wings();
		if (mask == null || mask.getWidth() < 46 || mask.getHeight() < 22) return;
		for (int y = 0; y < 22; y++) {
			for (int x = 22; x < 46; x++) {
				int vanilla = mask.getPixel(x, y);
				if (Colors.alpha(vanilla) < 16) {
					img.setPixel(x, y, 0);
					continue;
				}
				// vanilla feathers run from about 0.25 (shadow) to 0.6 (highlight) brightness
				float lum = (Colors.red(vanilla) + Colors.green(vanilla) + Colors.blue(vanilla)) / 765f;
				float shade = Math.max(0, Math.min(1, (lum - 0.22f) / 0.38f));
				int c;
				if (s == Style.COFFEE) {
					c = latte(mask, x, y, shade, accent, time);
				} else {
					float t = Math.max(0, Math.min(1, (y - 1) / 20f));
					c = wingColor(s, x, y, t, accent, accent2, time);
					c = shade > 0.5f ? Colors.lerp(c, 0xFFFFFFFF, (shade - 0.5f) * 0.35f) : Colors.shade(c, 0.6f + shade * 0.8f);
					// a soft rim where the wing meets the air
					if (edge(mask, x, y)) c = Colors.lerp(c, 0xFFFFFFFF, 0.3f);
				}
				img.setPixel(x, y, Colors.withAlpha(c, 255));
			}
		}
	}

	/** One Coffee wing texel: foam with a glint drifting down the pour, else espresso, with crema at the rim and around the foam. */
	private static int latte(NativeImage mask, int x, int y, float shade, int accent, float time) {
		int crema = Colors.lerp(0xFFB77A3E, accent, 0.35f);
		if (foam(x, y)) {
			float glint = (float) Math.pow(Math.max(0, Math.cos(y / 22f * 6 - time * 1.1f)), 10);
			int milk = Colors.lerp(Colors.lerp(0xFFF4E8D2, accent, 0.12f), 0xFFFFFFFF, glint * 0.4f);
			// foam keeps most of its brightness; the feathers only shade it lightly
			return Colors.shade(milk, 0.93f + shade * 0.12f);
		}
		int c;
		if (edge(mask, x, y)) {
			c = crema;
		} else {
			c = Colors.lerp(0xFF2B160B, 0xFF160A05, Math.max(0, Math.min(1, (y - 2) / 19f)));
			if (foam(x + 1, y) || foam(x - 1, y) || foam(x, y + 1) || foam(x, y - 1)) c = Colors.lerp(c, crema, 0.28f);
		}
		return Colors.shade(c, 0.8f + shade * 0.35f);
	}

	private static boolean foam(int x, int y) {
		int row = y - 3;
		if (row < 0 || row >= TULIP.length) return false;
		// the wing's middle drifts right toward the tip
		int i = (int) Math.floor(x + 0.5f - (39.5f + (y - 2) / 19f * 2) + 4.5f);
		return i >= 0 && i < 9 && TULIP[row].charAt(i) == '#';
	}

	private static boolean edge(NativeImage mask, int x, int y) {
		int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (int[] d : around) {
			int nx = x + d[0], ny = y + d[1];
			if (nx < 22 || nx >= 46 || ny < 0 || ny >= 22 || Colors.alpha(mask.getPixel(nx, ny)) < 16) return true;
		}
		return false;
	}

	private static int wingColor(Style s, int x, int y, float t, int accent, int accent2, float time) {
		return switch (s) {
			case GRADIENT -> Colors.lerp(accent, accent2, t);
			case SHIMMER -> {
				int g = Colors.lerp(accent2, accent, t);
				float band = (float) Math.pow(Math.max(0, Math.cos(((x + y) * 0.12f - time * 1.6f))), 16);
				yield Colors.lerp(g, 0xFFFFFFFF, band * 0.5f);
			}
			case COFFEE, CUSTOM, VANILLA -> 0xFF000000;
		};
	}

	/** Draw {@code argb} over the pixel already there. */
	private static void blend(NativeImage img, int x, int y, int argb) {
		int under = img.getPixel(x, y);
		float a = Colors.alpha(argb) / 255f;
		img.setPixel(x, y, Colors.withAlpha(Colors.lerp(Colors.withAlpha(under, 255), Colors.withAlpha(argb, 255), a), 255));
	}

	private static int base(Style s, int x, int y, int accent, int accent2, float time) {
		boolean inner = x >= 12 && x < 22;
		float fy = Math.min(1f, y / 17f);
		int c = switch (s) {
			case COFFEE -> Colors.lerp(0xFF3B2416, 0xFF1A0F09, fy);
			case GRADIENT -> Colors.lerp(accent, accent2, fy);
			case SHIMMER -> {
				int g = Colors.lerp(accent, accent2, fy);
				float band = (float) Math.pow(Math.max(0, Math.cos(((x + y) * 0.12f - time * 1.6f))), 16);
				yield Colors.lerp(g, 0xFFFFFFFF, band * 0.6f);
			}
			case CUSTOM, VANILLA -> 0xFF000000;
		};
		return Colors.withAlpha(inner ? Colors.shade(c, 0.7f) : c, 255);
	}

	@Override
	public void renderWorld(PoseStack pose, Vec3 cam) {
	}

	@Override
	protected void render(PoseStack pose, Player player, float partialTick, float bodyYaw) {
	}
}
