package dev.coffeeclient.module.modules.cosmetics;

import com.mojang.blaze3d.platform.NativeImage;
import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.anim.Motion;
import dev.coffeeclient.hud.HudElement;
import dev.coffeeclient.hud.ModuleCustomElement;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.ActionSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.StringSetting;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.GifFrames;
import dev.coffeeclient.util.Open;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** An animated GIF that sits on the screen (only you can see it). It is a HUD element: drag it in the HUD editor. */
public final class Pet extends Module {
	/** The bundled GIFs in assets/coffeeclient/pets, named after the file, and Custom for one of your own. */
	public enum Choice { CHIIKAWA, MARIO, NECO, RABBIT, TUNGTUNG, CUSTOM }

	/** Longest side of the GIF on the HUD, in GUI pixels: smaller GIFs keep their own size, bigger ones scale down to this. */
	private static final int MAX_SIZE = 256;
	/** Side of the box shown while no GIF is loaded, so the pet can still be found and moved in the HUD editor. */
	private static final int PLACEHOLDER = 32;
	/** How often a missing file is looked for again, in seconds. */
	private static final float RETRY_SECONDS = 2f;

	public final EnumSetting<Choice> gif = setting(new EnumSetting<>("Pet", "Which animated GIF sits on the screen: a bundled one, or your own", Choice.CHIIKAWA));
	public final StringSetting file = setting(new StringSetting("File", "Name of a GIF in config/coffeeclient/pets", "pet", 64));
	public final ActionSetting openFolder = setting(new ActionSetting("Open pets folder", "Open the folder custom GIFs are read from", this::openFolder));

	/** Loaded GIFs by source: a bundled name, or "custom/" and the file name. Only one custom file is kept. */
	private final Map<String, Sheet> sheets = new HashMap<>();
	/** When each source was last tried and found missing or unreadable. */
	private final Map<String, Float> lookedAt = new HashMap<>();
	/** Custom files that failed to decode, with the modified time they had then: not decoded again until it changes. */
	private final Map<String, FileTime> broken = new HashMap<>();
	/** Textures made so far, so each one gets a name of its own. */
	private int loads;

	public Pet() {
		super("Pet", Category.COSMETICS, "An animated GIF that sits on the screen (only you can see it). Drag it in the HUD editor.");
		file.visibleWhen(() -> gif.get() == Choice.CUSTOM);
		openFolder.visibleWhen(() -> gif.get() == Choice.CUSTOM);
	}

	@Override
	public List<HudElement> hudElements() {
		return List.of(new PetElement(this));
	}

	private static Path folder() {
		return FabricLoader.getInstance().getConfigDir().resolve(CoffeeClient.MOD_ID).resolve("pets");
	}

	private void openFolder() {
		try {
			Files.createDirectories(folder());
			Open.path(folder());
		} catch (Exception e) {
			info("Could not open " + folder() + ": " + e.getMessage());
		}
	}

	/** The frames packed into one texture, {@code columns} to a row, left to right. */
	private record Sheet(Identifier id, int width, int height, int[] delays, int columns, int sheetWidth, int sheetHeight) {
	}

	/** The GIF to show now, or null while it is missing or unreadable. */
	private @Nullable Sheet sheet() {
		Choice choice = gif.get();
		String key = choice == Choice.CUSTOM ? "custom/" + file.get() : choice.name().toLowerCase(Locale.ROOT);
		Sheet sheet = sheets.get(key);
		if (sheet != null) return sheet;
		float now = Motion.time();
		Float last = lookedAt.get(key);
		if (last != null && now - last < RETRY_SECONDS) return null;
		lookedAt.put(key, now);
		FileTime stamp = modified(choice == Choice.CUSTOM ? customPath() : null);
		if (stamp != null && stamp.equals(broken.get(key))) return null;
		sheet = load(choice, key);
		if (sheet == null) {
			if (stamp != null) broken.put(key, stamp);
			return null;
		}
		if (choice == Choice.CUSTOM) releaseCustom();
		sheets.put(key, sheet);
		return sheet;
	}

	/** Decodes the GIF and registers its frames as one texture; null if there is no such file or it cannot be read. */
	private @Nullable Sheet load(Choice choice, String key) {
		try (InputStream in = open(choice)) {
			if (in == null) return null;
			GifFrames frames = GifFrames.read(in, MAX_SIZE);
			int n = frames.frames().length, w = frames.width(), h = frames.height();
			int columns = GifFrames.columns(n, w, h), rows = (n + columns - 1) / columns;
			NativeImage image = new NativeImage(columns * w, rows * h, true);
			for (int f = 0; f < n; f++) {
				int ox = f % columns * w, oy = f / columns * h;
				for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) image.setPixel(ox + x, oy + y, frames.frames()[f][y * w + x]);
			}
			Identifier id = Identifier.fromNamespaceAndPath("coffeeclient", "pet/" + loads++);
			Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "coffeeclient:" + id.getPath(), image));
			return new Sheet(id, w, h, frames.delays(), columns, columns * w, rows * h);
		} catch (IOException | RuntimeException e) {
			CoffeeClient.LOG.warn("Pet {} unavailable: {}", key, e.toString());
			return null;
		}
	}

	/** Frees the texture of the custom GIF before this one, so files tried earlier do not stay in memory. */
	private void releaseCustom() {
		Iterator<Map.Entry<String, Sheet>> it = sheets.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, Sheet> e = it.next();
			if (!e.getKey().startsWith("custom/")) continue;
			Minecraft.getInstance().getTextureManager().release(e.getValue().id());
			it.remove();
		}
	}

	/** The stream for the bundled GIF, or for the custom file if it exists; null when there is none. */
	private InputStream open(Choice choice) throws IOException {
		if (choice != Choice.CUSTOM) return Pet.class.getResourceAsStream("/assets/coffeeclient/pets/" + choice.name().toLowerCase(Locale.ROOT) + ".gif");
		Path path = customPath();
		return path != null && Files.isRegularFile(path) ? Files.newInputStream(path) : null;
	}

	/** The custom file's path, or null when its name would lead outside the pets folder. */
	private @Nullable Path customPath() {
		String name = file.get();
		if (name.contains("/") || name.contains("\\") || name.contains("..")) return null;
		return folder().resolve(name.endsWith(".gif") ? name : name + ".gif");
	}

	/** When the file was last changed, or null if there is no such file. */
	private static @Nullable FileTime modified(@Nullable Path path) {
		if (path == null || !Files.isRegularFile(path)) return null;
		try {
			return Files.getLastModifiedTime(path);
		} catch (IOException e) {
			return null;
		}
	}

	/** The GIF on the HUD: in the bottom-right corner until moved, animated from the game's clock. */
	private static final class PetElement extends ModuleCustomElement {
		PetElement(Pet pet) {
			super(pet, "pet", "Pet");
		}

		private Sheet sheet() {
			return ((Pet) module).sheet();
		}

		@Override
		public boolean autoStacks() {
			return false;
		}

		@Override
		public int width() {
			Sheet s = sheet();
			return s == null ? PLACEHOLDER : s.width();
		}

		@Override
		public int height() {
			Sheet s = sheet();
			return s == null ? PLACEHOLDER : s.height();
		}

		@Override
		protected int[] defaultPosition(int screenW, int screenH) {
			return new int[] {screenW - width() - MARGIN, screenH - height() - MARGIN};
		}

		@Override
		public void render(Ui ui, int x, int y, boolean preview) {
			Sheet s = sheet();
			if (s == null) return;
			// with reduced motion the pet stands still on its first frame
			int frame = ui.theme.motion() ? GifFrames.frameAt(s.delays(), (long) (Motion.time() * 1000)) : 0;
			int w = s.width(), h = s.height();
			int u = frame % s.columns() * w, v = frame / s.columns() * h;
			int color = Colors.withAlpha(0xFFFFFF, Math.round(255 * Draw.alpha()));
			ui.g.blit(RenderPipelines.GUI_TEXTURED, s.id(), x, y, u, v, w, h, s.sheetWidth(), s.sheetHeight(), color);
		}
	}
}
