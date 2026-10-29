package dev.coffeeclient.gui.screen;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.gui.CoffeeScreen;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.anim.Anim;
import dev.coffeeclient.gui.anim.Ease;
import dev.coffeeclient.gui.anim.Motion;
import dev.coffeeclient.gui.fx.Ripple;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.gui.widget.Button;
import dev.coffeeclient.gui.widget.IconButton;
import dev.coffeeclient.gui.widget.Widget;
import dev.coffeeclient.module.modules.client.MainMenu;
import dev.coffeeclient.util.Colors;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * Coffee's title screen: a pixel-built logo whose letters drop in and ripple, the theme's backdrop over
 * the panorama, and a menu with a gliding selection bar.
 */
public class CoffeeTitleScreen extends CoffeeScreen {
	private static final String LOGO = "COFFEE";
	/** 5x7 glyphs, rows top to bottom. */
	private static final java.util.Map<Character, String[]> GLYPHS = java.util.Map.of(
		'C', new String[] {".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."},
		'O', new String[] {".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."},
		'F', new String[] {"#####", "#....", "#....", "####.", "#....", "#....", "#...."},
		'E', new String[] {"#####", "#....", "#....", "####.", "#....", "#....", "#####"});
	private final String version = FabricLoader.getInstance().getModContainer(CoffeeClient.MOD_ID)
		.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
	private final long openedAt = System.nanoTime();
	private final List<Button> menu = new ArrayList<>();
	private final Anim barY = new Anim(18f);
	private final Anim barW = new Anim(18f);
	private final Ripple ripple = new Ripple();
	private int logoX, logoY, pixel;

	public CoffeeTitleScreen() {
		super("Coffee Client", null);
	}

	@Override
	protected void build() {
		menu.clear();
		int w = layoutWidth(), h = layoutHeight();
		pixel = Math.max(3, Math.min(9, (w / 2) / (LOGO.length() * 6)));
		int logoW = LOGO.length() * 6 * pixel - pixel;
		logoX = (w - logoW) / 2;
		logoY = Math.max(10, h / 5 - pixel * 3);
		int bw = 150, bh = 18, gap = 4;
		int by = logoY + pixel * 7 + 30;
		add(new Button("Singleplayer", () -> minecraft.gui.setScreen(new SelectWorldScreen(this))), w, by, bw, bh);
		add(new Button("Multiplayer", () -> minecraft.gui.setScreen(new JoinMultiplayerScreen(this))), w, by += bh + gap, bw, bh);
		add(new Button("Options", () -> minecraft.gui.setScreen(new OptionsScreen(this, minecraft.options, false))), w, by += bh + gap, bw, bh);
		add(new Button("Coffee dashboard", () -> minecraft.gui.setScreen(new DashboardScreen(this))), w, by += bh + gap, bw, bh);
		// Accounts, proxies and the vanilla menu as a row of icon buttons under the dashboard, as wide as the menu.
		IconButton.Icon wifi = pixels(WIFI, false), wifiOn = pixels(WIFI, true);
		by += bh + gap;
		int left = (w - bw) / 2, cell = (bw - 2 * gap) / 3;
		iconButton(new Button("Accounts", () -> minecraft.gui.setScreen(new AccountsScreen(this))).icon(pixels(PERSON, false)), "Accounts", left, by, cell, bh);
		iconButton(new Button("Proxies", () -> minecraft.gui.setScreen(new ProxiesScreen(this)))
			.icon((ui, x, y) -> (CoffeeClient.proxies().active() == null ? wifi : wifiOn).draw(ui, x, y)), null, left + cell + gap, by, cell, bh)
			.tooltip(() -> List.of(CoffeeClient.proxies().active() == null ? "Proxies" : "Proxies (on)"));
		iconButton(new Button("Vanilla menu", () -> {
			MainMenu.useVanilla();
			minecraft.gui.setScreen(new TitleScreen());
		}).icon(pixels(BLOCK, false)), null, left + 2 * (cell + gap), by, bw - 2 * (cell + gap), bh)
			.tooltip("Vanilla menu", "Switch back with MainMenu's Menu setting");
		add(new Button("Quit", minecraft::stop).danger(), w, by += bh + gap, bw, bh);
	}

	/*
	 * Icon art, 14x14, anti-aliased: a digit 1-9 is the text colour (the accent when lit) at that many
	 * ninths of its opacity, a letter a-i the dim text colour the same way, 0 is empty. Made by sampling
	 * each shape at 8x8 points per pixel, with a contrast curve so only true edge pixels are soft.
	 */
	private static final String[] PERSON = {
		"00000000000000",
		"00000599500000",
		"00005999950000",
		"00009999990000",
		"00009999990000",
		"00005999950000",
		"00000599500000",
		"00000000000000",
		"00000222200000",
		"00059999995000",
		"00199999999100",
		"00899999999800",
		"00599999999500",
		"00000000000000"};
	private static final String[] WIFI = {
		"00000000000000",
		"00000000000000",
		"00089999998000",
		"09997300379990",
		"99400000000499",
		"80029999992008",
		"00999411499900",
		"00930000003900",
		"00005999950000",
		"00009811890000",
		"00000099000000",
		"00000199100000",
		"00000099000000",
		"00000000000000"};
	private static final String[] BLOCK = {
		"00000000000000",
		"00044444444000",
		"00999999999900",
		"04999999999940",
		"04999999999940",
		"0di00000000id0",
		"0di00000000id0",
		"0di00000000id0",
		"0di00000000id0",
		"0di00000000id0",
		"0di00000000id0",
		"00iiiiiiiiii00",
		"000dddddddd000",
		"00000000000000"};

	/** Draws {@code art} centred in the 16x16 icon box, one GUI pixel per character, a run of equal pixels at a time. */
	private static IconButton.Icon pixels(String[] art, boolean lit) {
		return (ui, x, y) -> {
			int main = lit ? ui.theme.accent : ui.theme.text, dim = ui.theme.dimText;
			int ox = x + (16 - art[0].length()) / 2, oy = y + (16 - art.length) / 2;
			for (int r = 0; r < art.length; r++) {
				String row = art[r];
				for (int c = 0; c < row.length(); ) {
					char ch = row.charAt(c);
					int end = c;
					while (end < row.length() && row.charAt(end) == ch) end++;
					int level = ch >= '1' && ch <= '9' ? ch - '0' : ch >= 'a' && ch <= 'i' ? ch - 'a' + 1 : 0;
					if (level > 0) {
						int color = ch <= '9' ? main : dim;
						Draw.rect(ui.g, ox + c, oy + r, end - c, 1, Colors.withAlpha(color, Colors.alpha(color) * level / 9));
					}
					c = end;
				}
			}
		};
	}

	private Widget iconButton(Button b, String tooltip, int x, int y, int width, int height) {
		menu.add(b);
		if (tooltip != null) b.tooltip(tooltip);
		return root.add(b).setBounds(x, y, width, height);
	}

	private void add(Button b, int w, int y, int bw, int bh) {
		menu.add(b);
		root.add(b).setBounds((w - bw) / 2, y, bw, bh);
	}

	/** The title screen has nowhere to go back to. */
	@Override
	public void onClose() {
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	protected boolean onBackgroundClick(double mx, double my, int button) {
		ripple.spawn(mx, my);
		return true;
	}

	@Override
	protected void renderContent(Ui ui) {
		Theme t = ui.theme;
		float age = (System.nanoTime() - openedAt) / 1e9f;
		ripple.draw(ui, t.accent(0xA0), 60);
		super.renderContent(ui);
		drawSelectionBar(ui);
		drawLogo(ui, age);
		Draw.textRight(ui.g, "Coffee Client " + version, layoutWidth() - 4, layoutHeight() - 12, Colors.withAlpha(t.dimText, 0xC0));
	}

	/**
	 * Hover highlight, drawn over the hovered button and kept inside its frame: an accent wash that
	 * slides between buttons, with a light sheen sweeping across it.
	 */
	private void drawSelectionBar(Ui ui) {
		Widget hovered = null;
		for (Button b : menu) if (b.contains(ui.mouseX, ui.mouseY)) hovered = b;
		if (hovered == null) return;
		Theme t = ui.theme;
		int y = Math.round(barY.update(ui, hovered.y));
		int w = Math.min(hovered.width, Math.round(barW.update(ui, hovered.width)));
		int x = hovered.x + (hovered.width - w) / 2;
		// Inset by one pixel so the button's frame stays visible around the wash.
		int ix = x + 1, iy = y + 1, iw = w - 2, ih = hovered.height - 2;
		if (iw <= 0 || ih <= 0) return;
		Draw.gradientH(ui.g, ix, iy, iw, ih, 0, 1, pos -> t.accentAt(pos, 0x26));
		if (t.motion()) Draw.sheen(ui.g, ix, iy, iw, ih, Draw.sheenPhase(ui.timeMs, 1.4f, 0), Colors.withAlpha(0xFFFFFF, 0x2A));
	}

	/** Each logo pixel drops in (bouncing, staggered by column), then rides a gentle wave. */
	private void drawLogo(Ui ui, float age) {
		Theme t = ui.theme;
		boolean motion = t.motion();
		int px = pixel;
		float parX = (ui.mouseX - layoutWidth() / 2f) * -0.01f, parY = (ui.mouseY - layoutHeight() / 2f) * -0.01f;
		int depth = Math.max(2, px / 2);
		// a sideways glitch band every few seconds
		int glitchRow = -1;
		float glitchShift = 0;
		if (motion && (age % 4.7f) > 4.5f) {
			glitchRow = (int) (Motion.hash((int) (age / 4.7f)) * 7);
			glitchShift = (Motion.hash((int) (age * 30)) - 0.5f) * px * 3;
		}
		for (int pass = 0; pass < 3; pass++) {
			for (int li = 0; li < LOGO.length(); li++) {
				String[] glyph = GLYPHS.get(LOGO.charAt(li));
				for (int r = 0; r < 7; r++) {
					for (int c = 0; c < 5; c++) {
						if (glyph[r].charAt(c) != '#') continue;
						int col = li * 6 + c;
						float delay = col * 0.025f + Motion.hash(col, r) * 0.12f;
						float drop = motion ? Ease.OUT_BOUNCE.apply((age - delay) / 0.7f) : 1f;
						if (drop <= 0) continue;
						float wave = motion ? (float) Math.sin(age * 2.2f + col * 0.35f) * px * 0.35f : 0;
						float x = logoX + col * px + parX + (r == glitchRow ? glitchShift : 0);
						float y = logoY + r * px + wave + parY - (1 - drop) * (logoY + px * 8);
						float pos = col / (float) (LOGO.length() * 6);
						switch (pass) {
							case 0 -> {
								// soft accent halo
								Draw.rect(ui.g, Math.round(x) - 1, Math.round(y) - 1, px + 2, px + 2, t.accentAt(pos, 0x22));
							}
							case 1 -> {
								// extrusion, darker as it goes back
								for (int d = depth; d >= 1; d--) {
									int shade = Colors.shade(t.accentAt(pos), 0.25f + 0.25f * (1 - d / (float) depth));
									Draw.rect(ui.g, Math.round(x) + d, Math.round(y) + d, px, px, shade);
								}
							}
							default -> {
								int face = t.accentAt(pos);
								// a bright sweep travels across the letters
								float sweep = (float) Math.pow(Math.max(0, Math.cos((pos - (age * 0.25f % 1.6f) + 0.3f) * Math.PI)), 18);
								face = Colors.lerp(face, 0xFFFFFFFF, sweep * 0.7f);
								Draw.rect(ui.g, Math.round(x), Math.round(y), px, px, face);
								Draw.rect(ui.g, Math.round(x), Math.round(y), px, 1, Colors.withAlpha(0xFFFFFF, 0x50));
							}
						}
					}
				}
			}
		}
		if (glitchRow >= 0) Draw.rect(ui.g, logoX - px, logoY + glitchRow * px, LOGO.length() * 6 * px + px, Math.max(1, px / 3), t.accent(0x90));
	}
}
