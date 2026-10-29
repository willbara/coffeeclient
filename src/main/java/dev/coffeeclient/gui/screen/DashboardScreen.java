package dev.coffeeclient.gui.screen;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.core.Keybinds;
import dev.coffeeclient.gui.CoffeeScreen;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.widget.IconButton;
import dev.coffeeclient.gui.widget.KeyValueRow;
import dev.coffeeclient.gui.widget.LogFilterBar;
import dev.coffeeclient.gui.widget.LogView;
import dev.coffeeclient.gui.widget.TextField;
import dev.coffeeclient.gui.widget.ModulePerfTable;
import dev.coffeeclient.gui.widget.Panel;
import dev.coffeeclient.gui.widget.Sparkline;
import dev.coffeeclient.gui.widget.ProgressBar;
import dev.coffeeclient.gui.widget.Widget;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.coffeeclient.util.Coords;
import dev.coffeeclient.util.GameStats;
import dev.coffeeclient.util.KeyNames;
import dev.coffeeclient.util.Skins;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Landing screen: stat cards, World and Performance panels, navigation buttons and a live log tail.
 */
public class DashboardScreen extends CoffeeScreen {
	private static final int GAP = 4;
	/** FPS over the last minute, kept across openings. */
	private static final Sparkline.History FPS_HISTORY = new Sparkline.History(60, 1000,
		() -> net.minecraft.client.Minecraft.getInstance().getFps());

	public DashboardScreen(@Nullable Screen parent) {
		super("Dashboard", parent);
	}

	@Override
	protected @Nullable KeyMapping toggleKey() {
		return CoffeeClient.keybinds().dashboard;
	}

	@Override
	protected void build() {
		int width = layoutWidth(), height = layoutHeight();
		int contentW = Math.min(width - 16, 680);
		int x0 = (width - contentW) / 2;
		int y = 10;
		if (theme().glass()) {
			root.add(new GreetingBar(headIcon())).setBounds(x0, y - 2, contentW, GreetingBar.HEIGHT);
			y += GreetingBar.HEIGHT + GAP;
		}

		// world + performance panels
		int panelW = (contentW - GAP) / 2;
		int rowsH = 4 * KeyValueRow.HEIGHT + 2;
		int panelH = Panel.HEADER + 3 + rowsH + 4;
		Panel world = root.add(new Panel("World").subtitle(() -> GameStats.dimensionShort(minecraft.level)));
		world.setBounds(x0, y, panelW, panelH);
		addRows(world,
			new KeyValueRow("Seed", this::seed).copyable(),
			new KeyValueRow("Dimension", () -> GameStats.dimensionId(minecraft.level)).copyable(),
			new KeyValueRow("Position", this::position).copyable(),
			new KeyValueRow("Chunk · biome", this::chunkAndBiome));

		Panel perf = root.add(new Panel("Performance").subtitle(() -> minecraft.getFps() + " FPS"));
		perf.setBounds(x0 + panelW + GAP, y, contentW - panelW - GAP, panelH);
		int py = perf.contentY();
		perf.add(new KeyValueRow("Memory", () -> GameStats.usedMemoryMb() + " / " + GameStats.maxMemoryMb() + " MB"))
			.setBounds(perf.contentX(), py, perf.contentWidth(), KeyValueRow.HEIGHT);
		perf.add(new ProgressBar(() -> GameStats.usedMemoryMb() / (double) Math.max(1, GameStats.maxMemoryMb())))
			.setBounds(perf.contentX(), py + KeyValueRow.HEIGHT + 1, perf.contentWidth(), 3);
		perf.add(new KeyValueRow("Chunks loaded", () -> Integer.toString(GameStats.loadedChunks())))
			.setBounds(perf.contentX(), py + KeyValueRow.HEIGHT + 6, perf.contentWidth(), KeyValueRow.HEIGHT);
		int graphY = py + KeyValueRow.HEIGHT * 2 + 8;
		perf.add(new Sparkline(FPS_HISTORY, "fps")).setBounds(perf.contentX(), graphY, perf.contentWidth(), perf.contentY() + rowsH - graphY);
		y += panelH + GAP;

		// navigation buttons
		Keybinds keys = CoffeeClient.keybinds();
		record Nav(IconButton.Icon icon, String title, KeyMapping key, Supplier<Screen> screen) {
		}
		List<Nav> navs = List.of(
			new Nav(icon(Items.FEATHER, "item/feather"), "ClickGUI", keys.clickGui, () -> new ClickGuiScreen(this)),
			new Nav(icon(Items.SPYGLASS, "item/spyglass"), "HUD Editor", keys.hudEditor, () -> new HudEditorScreen(this)),
			new Nav(icon(Items.LEVER, "block/lever"), "Macros", keys.macros, () -> new MacrosScreen(this)),
			new Nav(icon(Items.WRITABLE_BOOK, "item/writable_book"), "Profiles", keys.profiles, () -> new ProfilesScreen(this)),
			new Nav(headIcon(), "Friends", keys.friends, () -> new FriendsScreen(this)),
			new Nav(icon(Items.BRUSH, "item/brush"), "Settings", keys.settings, () -> new SettingsScreen(this)),
			new Nav(icon(Items.NAME_TAG, "item/name_tag"), "Keybinds", keys.keybindList, () -> new KeybindsScreen(this)),
			new Nav(icon(Items.FILLED_MAP, "item/filled_map"), "History", keys.history, () -> new HistoryScreen(this)));
		int perNavRow = contentW >= 360 ? 4 : 2;
		int navW = (contentW - (perNavRow - 1) * GAP) / perNavRow;
		for (int i = 0; i < navs.size(); i++) {
			Nav nav = navs.get(i);
			int col = i % perNavRow, row = i / perNavRow;
			root.add(new IconButton(nav.icon(), nav.title(), () -> KeyNames.name(nav.key()), () -> minecraft.gui.setScreen(nav.screen().get())))
				.setBounds(x0 + col * (navW + GAP), y + row * (IconButton.HEIGHT + GAP), navW, IconButton.HEIGHT);
		}
		int navRows = (navs.size() + perNavRow - 1) / perNavRow;
		y += navRows * (IconButton.HEIGHT + GAP);

		// module cost (measured only while this panel is on screen); only in a world, where modules run
		// Rows shrink on short screens so the log keeps room; skipped when even two rows do not fit.
		int perfRows = Math.min(6, (height - y - 8 - MIN_LOG_H - GAP - Panel.HEADER - 7) / ModulePerfTable.ROW - 1);
		if (minecraft.level != null && perfRows >= 2) {
			int tableH = ModulePerfTable.ROW * (perfRows + 1);
			int perfH = Panel.HEADER + 3 + tableH + 4;
			Panel perfPanel = root.add(new Panel("Module performance").subtitle(() -> "ms / s"));
			perfPanel.setBounds(x0, y, contentW, perfH);
			perfPanel.add(new ModulePerfTable()).setBounds(perfPanel.contentX(), perfPanel.contentY(), perfPanel.contentWidth(), tableH);
			y += perfH + GAP;
		}

		// log panel: fills the rest unless the user dragged it shorter
		int available = Math.max(MIN_LOG_H, height - y - 8);
		int saved = CoffeeClient.options().dashboardLogHeight.intValue();
		int logH = saved > 0 ? Math.max(MIN_LOG_H, Math.min(available, saved)) : available;
		Panel log = root.add(new Panel("Log"));
		log.setBounds(x0, y, contentW, logH);
		// Filter chips on the left, search on the right, the lines below.
		int searchW = Math.min(140, log.contentWidth() / 3);
		log.add(new LogFilterBar()).setBounds(log.contentX(), log.contentY() - 1, log.contentWidth() - searchW - 4, LogFilterBar.HEIGHT);
		log.add(new TextField().placeholder("Search log").maxLength(64).text(LogView.FILTER.search).onChange(str -> {
			LogView.FILTER.search = str;
			LogView.FILTER.changed();
		})).setBounds(log.contentX() + log.contentWidth() - searchW, log.contentY() - 2, searchW, LogFilterBar.HEIGHT + 2);
		int viewTop = log.contentY() + LogFilterBar.HEIGHT + 2;
		LogView view = log.add(new LogView());
		view.setBounds(log.contentX() - 2, viewTop, log.contentWidth() + 4, log.contentY() + log.contentHeight() - viewTop);
		root.add(new ResizeHandle(log, view, available)).setBounds(x0, y + logH - 3, contentW, 6);
	}

	private static final int MIN_LOG_H = 56;

	/**
	 * The item itself, or on the title screen its texture: until a world loads, items have no data
	 * components and an ItemStack can't be made.
	 */
	private static IconButton.Icon icon(Item item, String texture) {
		try {
			return IconButton.Icon.of(new ItemStack(item));
		} catch (RuntimeException e) {
			return IconButton.Icon.texture(Identifier.withDefaultNamespace("textures/" + texture + ".png"));
		}
	}

	/**
	 * Your player head. On the title screen no item can exist yet (item components are bound when a world
	 * loads), so it is your skin's face instead (the default skin until yours loads).
	 */
	private static IconButton.Icon headIcon() {
		try {
			return IconButton.Icon.of(Skins.ownHead());
		} catch (RuntimeException e) {
			var profile = Minecraft.getInstance().getGameProfile();
			var skin = Minecraft.getInstance().getSkinManager().get(profile);
			return (ui, x, y) -> PlayerFaceExtractor.extractRenderState(ui.g,
				skin.getNow(Optional.empty()).orElseGet(() -> DefaultPlayerSkin.get(profile)), x, y, 16);
		}
	}

	/** Drag the log panel's bottom edge to resize it; right-click resets to "fill". */
	private static final class ResizeHandle extends Widget {
		private final Panel log;
		private final LogView view;
		private final int maxHeight;
		private boolean dragging;

		ResizeHandle(Panel log, LogView view, int maxHeight) {
			this.log = log;
			this.view = view;
			this.maxHeight = maxHeight;
			tooltip("Drag to resize the log", "Right-click: fill the screen");
		}

		private void apply(int newHeight) {
			int h = Math.max(MIN_LOG_H, Math.min(maxHeight, newHeight));
			log.height = h;
			view.height = log.contentY() + log.contentHeight() - view.y;
			y = log.y + h - 3;
			CoffeeClient.options().dashboardLogHeight.set(h >= maxHeight ? 0.0 : (double) h);
		}

		@Override
		protected void draw(Ui ui) {
			boolean active = dragging || hover > 0.5f;
			if (active) ui.g.requestCursor(CursorTypes.RESIZE_NS);
			int c = active ? ui.theme.accent : ui.theme.border;
			int cx = x + width / 2;
			if (ui.theme.glass()) {
				// a single pill grabber, like a sheet handle
				dev.coffeeclient.gui.fx.Shapes.roundRect(ui.g, cx - 12, y + 2, 24, 2, 1, active ? c : dev.coffeeclient.util.Colors.withAlpha(0xFFFFFF, 0x40));
				return;
			}
			Draw.rect(ui.g, cx - 10, y + 2, 21, 1, c);
			Draw.rect(ui.g, cx - 6, y + 4, 13, 1, c);
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			if (button == RIGHT) {
				apply(maxHeight);
				return true;
			}
			dragging = button == LEFT;
			return dragging;
		}

		@Override
		public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
			if (!dragging) return false;
			apply((int) Math.round(my - log.y));
			return true;
		}

		@Override
		public boolean mouseReleased(double mx, double my, int button) {
			boolean was = dragging;
			dragging = false;
			return was;
		}
	}

	/**
	 * Glass dashboard header: your face in a round well, a greeting for the time of day and your name, and
	 * status pills on the right (where you are, and the time).
	 */
	private static final class GreetingBar extends Widget {
		static final int HEIGHT = 24;
		private final IconButton.Icon face;

		GreetingBar(IconButton.Icon face) {
			this.face = face;
		}

		private static String greeting() {
			int hour = java.time.LocalTime.now().getHour();
			return hour < 5 ? "Up late," : hour < 12 ? "Good morning," : hour < 18 ? "Good afternoon," : "Good evening,";
		}

		private static String where() {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null) return "Main menu";
			if (mc.hasSingleplayerServer()) return "Singleplayer";
			var server = mc.getCurrentServer();
			if (server == null) return "Multiplayer";
			return dev.coffeeclient.module.modules.client.StreamerMode.hideServer() ? "Multiplayer" : server.ip;
		}

		@Override
		protected void draw(Ui ui) {
			var t = ui.theme;
			int cy = y + HEIGHT / 2;
			dev.coffeeclient.gui.fx.Shapes.roundRect(ui.g, x, cy - 11, 22, 22, 11, t.control);
			dev.coffeeclient.gui.fx.Shapes.roundOutline(ui.g, x, cy - 11, 22, 22, 11, dev.coffeeclient.util.Colors.withAlpha(0xFFFFFF, 0x38), dev.coffeeclient.util.Colors.withAlpha(0xFFFFFF, 0x0C));
			if (Draw.alpha() > 0.6f) face.draw(ui, x + 3, cy - 8);
			Draw.text(ui.g, greeting(), x + 28, cy - 9, t.dimText);
			// PlayerProtect swaps the name inside text drawing, so the real one is safe to pass
			Draw.text(ui.g, Minecraft.getInstance().getUser().getName(), x + 28, cy + 1, t.strongText());
			// right: status pills
			int right = x + width;
			right = pill(ui, right, cy, java.time.LocalTime.now().format(CLOCK), false);
			pill(ui, right - 4, cy, Draw.trim(where(), Math.max(40, width / 3)), Minecraft.getInstance().level != null);
		}

		/** A status pill ending at {@code right}; returns its left edge. */
		private int pill(Ui ui, int right, int cy, String text, boolean live) {
			var t = ui.theme;
			int w = Draw.width(text) + (live ? 18 : 12), h = 14, px = right - w;
			Draw.box(ui.g, px, cy - h / 2, w, h, dev.coffeeclient.util.Colors.withAlpha(t.control, 0xC0), dev.coffeeclient.util.Colors.withAlpha(0xFFFFFF, 0x24));
			int tx = px + 6;
			if (live) {
				dev.coffeeclient.gui.fx.Shapes.glowDot(ui.g, px + 7.5f, cy, 2, 3, t.accent(0x90));
				dev.coffeeclient.gui.fx.Shapes.disc(ui.g, px + 7.5f, cy, 2, t.accent);
				tx += 6;
			}
			Draw.text(ui.g, text, tx, cy - 4, t.text);
			return px;
		}
	}

	private static final java.time.format.DateTimeFormatter CLOCK = java.time.format.DateTimeFormatter.ofPattern("HH:mm");

	private static void addRows(Panel panel, KeyValueRow... rows) {
		int ry = panel.contentY();
		for (KeyValueRow row : rows) {
			panel.add(row).setBounds(panel.contentX(), ry, panel.contentWidth(), KeyValueRow.HEIGHT);
			ry += KeyValueRow.HEIGHT;
		}
	}

	private String seed() {
		if (dev.coffeeclient.module.modules.client.StreamerMode.hideSeed()) return dev.coffeeclient.module.modules.client.StreamerMode.HIDDEN;
		var seed = GameStats.seed();
		return seed.isPresent() ? Long.toString(seed.getAsLong()) : GameStats.UNKNOWN;
	}

	private String position() {
		LocalPlayer p = minecraft.player;
		if (p == null) return "-";
		if (dev.coffeeclient.module.modules.client.StreamerMode.hideCoords()) return dev.coffeeclient.module.modules.client.StreamerMode.HIDDEN;
		BlockPos pos = p.blockPosition();
		return Coords.format(pos.getX()) + " " + Coords.format(pos.getY()) + " " + Coords.format(pos.getZ());
	}

	private String chunkAndBiome() {
		LocalPlayer p = minecraft.player;
		ClientLevel level = minecraft.level;
		if (p == null || level == null) return "-";
		if (dev.coffeeclient.module.modules.client.StreamerMode.hideCoords()) return GameStats.biomeName(level, p.blockPosition());
		var chunk = p.chunkPosition();
		return Coords.format(chunk.x()) + " " + Coords.format(chunk.z()) + " · " + GameStats.biomeName(level, p.blockPosition());
	}
}
