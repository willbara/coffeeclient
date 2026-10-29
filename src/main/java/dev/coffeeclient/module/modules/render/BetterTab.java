package dev.coffeeclient.module.modules.render;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.module.modules.misc.Friends;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.setting.StringListSetting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.PlayerTeam;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Tab list improvements: sorting (rank prefix, name, ping, friends first), more players and columns,
 * highlighted self and friends, numeric ping and game modes. The numeric ping absorbs Coffee's former TabPing.
 */
public final class BetterTab extends Module {
	public enum SortBy {
		Vanilla, Rank, Name, Ping
	}

	public final NumberSetting tabSize = group(new NumberSetting("Max players", "How many players the tab list shows in total", 100, 1, 1000, 1), "Layout");
	public final NumberSetting columnHeight = group(new NumberSetting("Column height", "How many players each column holds", 20, 1, 1000, 1), "Layout");

	public final EnumSetting<SortBy> sortBy = group(new EnumSetting<>("Sort by",
		"Vanilla: the server's order. Rank: group by the prefix before each name. Name: A-Z. Ping: lowest first", SortBy.Vanilla), "Sorting");
	public final BooleanSetting friendsFirst = group(new BooleanSetting("Friends first", "Put friends at the top, whatever the sort", false), "Sorting");
	public final StringListSetting rankPriority = group(new StringListSetting("Rank priority",
		"Ranks in order, highest first (matched against the prefix, case-insensitive). Unlisted ranks follow, grouped by prefix", List.of(), 32), "Sorting");

	public final BooleanSetting highlightSelf = group(new BooleanSetting("Highlight self", "Colour your own name", true), "Names");
	public final ColorSetting selfColor = group(new ColorSetting("Self color", "Colour of your own name", 0xFFFA821E), "Names");
	public final BooleanSetting highlightFriends = group(new BooleanSetting("Highlight friends", "Colour friends' names", true), "Names");
	public final ColorSetting friendColor = group(new ColorSetting("Friend color", "Colour of friends' names", 0xFF5CD65C), "Names");
	public final BooleanSetting gamemode = group(new BooleanSetting("Gamemode", "Show each player's game mode after their name", false), "Names");

	public final BooleanSetting accurateLatency = group(new BooleanSetting("Accurate latency", "Show ping as a number instead of signal bars", true), "Ping");
	public final BooleanSetting colorize = group(new BooleanSetting("Colorize", "Green/amber/red by latency", true), "Ping");
	public final BooleanSetting suffix = group(new BooleanSetting("Show \"ms\"", "Append ms to the number", true), "Ping");
	public final NumberSetting good = group(new NumberSetting("Good below", "Green below this many ms", 150, 20, 500, 10), "Ping");
	public final NumberSetting bad = group(new NumberSetting("Bad above", "Red above this many ms", 300, 100, 2000, 50), "Ping");

	public BetterTab() {
		super("BetterTab", List.of("TabPing", "Better Tab"), Category.RENDER, "Bigger tab list, highlighted names, numeric ping and game modes.");
		rankPriority.visibleWhen(() -> sortBy.get() == SortBy.Rank);
		selfColor.visibleWhen(highlightSelf::get);
		friendColor.visibleWhen(highlightFriends::get);
		for (Setting<?> s : List.of(colorize, suffix, good, bad)) s.visibleWhen(accurateLatency::get);
		good.visibleWhen(() -> accurateLatency.get() && colorize.get());
		bad.visibleWhen(() -> accurateLatency.get() && colorize.get());
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	/** The module when on, else null (mixin hooks). */
	public static BetterTab active() {
		return ModuleManager.ifOn(BetterTab.class);
	}

	@Override
	public StringListSetting embeddedList() {
		return sortBy.get() == SortBy.Rank ? rankPriority : null;
	}

	/**
	 * The tab list order: friends first if wanted, then the chosen sort, with vanilla's order (game
	 * mode, server team order, name) breaking ties. Built per sort so prefixes are parsed once per player.
	 */
	public Comparator<PlayerInfo> comparator(Comparator<PlayerInfo> vanilla) {
		Comparator<PlayerInfo> order = switch (sortBy.get()) {
			case Vanilla -> vanilla;
			case Name -> Comparator.<PlayerInfo, String>comparing(i -> i.getProfile().name(), String.CASE_INSENSITIVE_ORDER).thenComparing(vanilla);
			case Ping -> Comparator.comparingInt(PlayerInfo::getLatency).thenComparing(vanilla);
			case Rank -> rankOrder(vanilla);
		};
		if (!friendsFirst.get()) return order;
		return Comparator.comparing((PlayerInfo i) -> !Friends.is(i.getProfile().name(), dev.coffeeclient.friend.FriendTrait.HIGHLIGHTED)).thenComparing(order);
	}

	private Comparator<PlayerInfo> rankOrder(Comparator<PlayerInfo> vanilla) {
		List<String> priority = rankPriority.get().stream().map(r -> r.toLowerCase(Locale.ROOT)).toList();
		Map<PlayerInfo, String> prefixes = new IdentityHashMap<>();
		Map<PlayerInfo, Integer> ranks = new IdentityHashMap<>();
		Function<PlayerInfo, String> prefix = info -> prefixes.computeIfAbsent(info, BetterTab::prefixOf);
		Function<PlayerInfo, Integer> rank = info -> ranks.computeIfAbsent(info, i -> {
			String p = prefix.apply(i).toLowerCase(Locale.ROOT);
			if (p.isEmpty()) return Integer.MAX_VALUE;
			for (int r = 0; r < priority.size(); r++) if (p.contains(priority.get(r))) return r;
			return priority.size();
		});
		return Comparator.comparing(rank)
			// Unlisted ranks: group identical prefixes together.
			.thenComparing(prefix, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(vanilla);
	}

	/** The text in front of a player's name in the tab list (their rank), else their team prefix. */
	private static String prefixOf(PlayerInfo info) {
		String name = info.getProfile().name();
		Component display = info.getTabListDisplayName();
		if (display != null) {
			String plain = ChatFormatting.stripFormatting(display.getString());
			int at = plain == null ? -1 : plain.indexOf(name);
			if (at > 0) return plain.substring(0, at).trim();
		}
		PlayerTeam team = info.getTeam();
		if (team != null) {
			String teamPrefix = ChatFormatting.stripFormatting(team.getPlayerPrefix().getString());
			if (teamPrefix != null) return teamPrefix.trim();
		}
		return "";
	}

	/** Name shown in the tab list for {@code info}. */
	public Component displayName(PlayerInfo info) {
		Component name = info.getTabListDisplayName();
		if (name == null) name = Component.literal(info.getProfile().name());

		Minecraft mc = mc();
		Integer color = null;
		if (highlightSelf.get() && mc.player != null && info.getProfile().id().equals(mc.player.getGameProfile().id())) {
			color = selfColor.argb();
		} else if (highlightFriends.get() && Friends.is(info.getProfile().name(), dev.coffeeclient.friend.FriendTrait.HIGHLIGHTED)) {
			color = Friends.color(info.getProfile().name(), friendColor.argb());
		}
		if (color != null) {
			// Legacy colour codes in the display name would override the highlight.
			String plain = name.getString();
			for (ChatFormatting format : ChatFormatting.values()) {
				if (TextColor.fromLegacyFormat(format) != null) plain = plain.replace(format.toString(), "");
			}
			name = Component.literal(plain).setStyle(name.getStyle().withColor(TextColor.fromRgb(color & 0xFFFFFF)));
		}

		if (gamemode.get()) {
			GameType mode = info.getGameMode();
			String text = mode == null ? "?" : switch (mode) {
				case SURVIVAL -> "S";
				case CREATIVE -> "C";
				case ADVENTURE -> "A";
				case SPECTATOR -> "Sp";
			};
			MutableComponent withMode = Component.empty().append(name);
			withMode.append(Component.literal(" [" + text + "]").withStyle(ChatFormatting.GRAY));
			name = withMode;
		}
		return name;
	}

	/** Draws the numeric ping; false leaves vanilla's signal bars. */
	public boolean drawPing(GuiGraphicsExtractor g, int slotWidth, int xo, int yo, int latency) {
		if (!accurateLatency.get()) return false;
		String text = latency < 0 ? "?" : Math.min(latency, 9999) + (suffix.get() ? "ms" : "");
		int color = 0xFFFFFFFF;
		if (colorize.get() && latency >= 0) {
			color = latency < good.get() ? 0xFF5CD65C : latency > bad.get() ? 0xFFE0584F : 0xFFF0B030;
		}
		Draw.text(g, text, xo + slotWidth - Draw.width(text) - 1, yo, color);
		return true;
	}

	/** Rows and columns for {@code players} entries under the column-height limit. */
	public int[] layout(int players) {
		int rows = players, cols = 1;
		while (rows > columnHeight.intValue()) {
			cols++;
			rows = (players + cols - 1) / cols;
		}
		return new int[] {rows, cols};
	}
}
