package dev.coffeeclient.module.modules.misc;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.setting.StringListSetting;
import dev.coffeeclient.util.Sounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat tweaks: hides or censors chat lines containing keywords (plus a built-in profanity list in
 * Family Friendly mode), stacks repeated messages into one line with a counter,
 * and pings you when someone mentions your name. Was ChatFilter.
 */
public final class ChatTweaks extends Module {
	public enum Action { HIDE_MESSAGE, CENSOR_WORD }

	/** Which built-in word list to apply on top of your own Keywords. */
	public enum Level { NONE, CASUAL, FAMILY_FRIENDLY }

	private static final String ENDINGS = "(?:s|es|ed|er|ers|ing|in|y|ie|ies|z|a|as)?";
	private static final String LIST_DIR = "/assets/coffeeclient/chatfilter/";
	private static List<String> slurs, profanity;

	public final EnumSetting<Level> level = group(new EnumSetting<>("Built-in list",
		"Casual: slurs, hate speech and abuse only (swearing allowed). Family Friendly: also all profanity and sexual terms", Level.NONE), "Filter");
	public final EnumSetting<Action> action = group(new EnumSetting<>("Action", "Hide the whole line, or replace just the matching word with ****", Action.CENSOR_WORD), "Filter");
	public final BooleanSetting wholeWords = group(new BooleanSetting("Whole words", "Only match whole words (\"ass\" won't match \"class\")", true), "Filter");
	public final StringListSetting keywords = group(new StringListSetting("Keywords", "Words or phrases to filter (case-insensitive)", List.of(), 64), "Filter");
	public final BooleanSetting useFile = group(new BooleanSetting("Word list file", "Also filter every line of chat_filter.txt in the Coffee config folder (re-read when it changes)", false), "Filter");
	public final BooleanSetting filterItems = group(new BooleanSetting("Item names", "Censor the same words in item names", false), "Beyond chat");
	public final BooleanSetting filterTitles = group(new BooleanSetting("Titles", "Censor the same words in titles and subtitles", false), "Beyond chat");
	public final BooleanSetting filterSigns = group(new BooleanSetting("Signs", "Censor the same words on signs", false), "Beyond chat");
	public final BooleanSetting filterBooks = group(new BooleanSetting("Books", "Censor the same words in written books", false), "Beyond chat");
	public final BooleanSetting regexKeywords = group(new BooleanSetting("Regex keywords", "Read keywords as regular expressions (a broken one is matched as plain text)", false), "Filter");
	public final BooleanSetting chatHeads = group(new BooleanSetting("Player heads", "Draw the sender's head before each chat line", false), "History");
	public final BooleanSetting keepHistory = group(new BooleanSetting("Keep across reconnects", "Don't clear chat when you leave a server", false), "History");
	public final NumberSetting historyLength = group(new NumberSetting("History length", "Chat lines kept (vanilla keeps 100)", 100, 100, 5000, 100), "History");
	public final StringListSetting mutedPlayers = group(new StringListSetting("Muted players", "Names or UUIDs whose chat lines are hidden", List.of(), 36), "Mute");
	public final BooleanSetting muteAll = group(new BooleanSetting("Mute all chat", "Hide every server chat line except the kinds allowed below", false), "Mute");
	public final BooleanSetting allowJoinLeave = group(new BooleanSetting("Allow join/leave", "Keep \"joined the game\" and \"left the game\" lines", true), "Mute");
	public final BooleanSetting allowWhispers = group(new BooleanSetting("Allow whispers", "Keep private messages sent to or from you", true), "Mute");
	public final BooleanSetting allowDeaths = group(new BooleanSetting("Allow death messages", "Keep death and kill lines", false), "Mute");
	public final BooleanSetting allowFriends = group(new BooleanSetting("Allow friends", "Keep everything a friend says", true), "Mute");
	public final BooleanSetting stackRepeats = group(new BooleanSetting("Stack repeats", "Collapse a repeated message into one line with a counter", true), "AntiSpam");
	public final BooleanSetting similar = group(new BooleanSetting("Similar repeats", "Also stack lines that differ only in case, numbers or spacing", true), "AntiSpam");
	public final NumberSetting lookBack = group(new NumberSetting("Look back", "How many recent messages a repeat is compared with", 20, 1, 100, 1), "AntiSpam");
	public final BooleanSetting mentions = group(new BooleanSetting("Mentions", "Highlight and ping when someone writes your name", false), "Mentions");
	public final StringListSetting nicknames = group(new StringListSetting("Nicknames", "Other names that count as you (2+ letters)", List.of(), 32), "Mentions");
	public final BooleanSetting partialNames = group(new BooleanSetting("Partial names", "Also the first 4 letters and the parts of your name (\"Steve\" in \"Steve_Builder\")", true), "Mentions");
	public final ColorSetting mentionColor = group(new ColorSetting("Highlight", "Colour of your name in the message", 0xFF55FFFF), "Mentions");
	public final IdListSetting mentionSound = group(new IdListSetting("Sound", "Sound played on a mention (click ▶ to preview)",
		List.of("minecraft:block.note_block.chime"), Sounds::allIds, true).labels(Sounds::label).preview(id -> {
		Sounds.play(id, 1f, 1f);
		return id;
	}), "Mentions");
	public final NumberSetting mentionVolume = group(new NumberSetting("Volume", "Mention sound volume (0 = silent)", 1.0, 0, 1, 0.05), "Mentions");
	public final BooleanSetting mentionToast = group(new BooleanSetting("Toast", "Also show a toast", false), "Mentions");
	/** Stack key and count of each message added while stacking was on (client thread). */
	private final Map<GuiMessage, Stack> stacks = new IdentityHashMap<>();

	private record Stack(String key, int count) {
	}

	private Pattern cached;
	private String cachedKey = "";

	public ChatTweaks() {
		super("ChatTweaks", List.of("ChatFilter", "AntiSpam", "Chat Filter", "Chat Tweaks", "Mention"), Category.MISC,
			"Filters chat, mutes players, stacks repeated messages and pings you when you're mentioned.");
		for (Setting<?> s : List.of(allowJoinLeave, allowWhispers, allowDeaths, allowFriends)) s.visibleWhen(muteAll::get);
		similar.visibleWhen(stackRepeats::get);
		lookBack.visibleWhen(stackRepeats::get);
		for (Setting<?> s : List.of(nicknames, partialNames, mentionColor, mentionSound, mentionVolume, mentionToast)) s.visibleWhen(mentions::get);
	}

	@Override
	protected void onDisable() {
		stacks.clear();
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public StringListSetting embeddedList() {
		return keywords;
	}

	private static List<String> load(String file) {
		try (var in = ChatTweaks.class.getResourceAsStream(LIST_DIR + file)) {
			if (in == null) return List.of();
			return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
		} catch (java.io.IOException e) {
			return List.of();
		}
	}

	private List<String> fileWords = List.of();
	private long fileStamp = -1, fileCheckedAt;

	/** The lines of chat_filter.txt, re-read at most every 5 seconds when its time stamp moves. */
	private List<String> fileWords() {
		long now = System.currentTimeMillis();
		if (now - fileCheckedAt < 5000) return fileWords;
		fileCheckedAt = now;
		java.nio.file.Path file = CoffeeClient.config().directory().resolve("chat_filter.txt");
		try {
			long stamp = java.nio.file.Files.exists(file) ? java.nio.file.Files.getLastModifiedTime(file).toMillis() : 0;
			if (stamp != fileStamp) {
				fileStamp = stamp;
				fileWords = stamp == 0 ? List.of() : java.nio.file.Files.readAllLines(file).stream().map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
			}
		} catch (java.io.IOException e) {
			fileWords = List.of();
		}
		return fileWords;
	}

	public enum Where { ITEM, TITLE, SIGN, BOOK }

	/** The chat filter's words censored in text outside chat, where that is switched on. */
	public static Component filterText(Component text, Where where) {
		ChatTweaks self = active(false);
		if (self == null || text == null) return text;
		boolean on = switch (where) {
			case ITEM -> self.filterItems.get();
			case TITLE -> self.filterTitles.get();
			case SIGN -> self.filterSigns.get();
			case BOOK -> self.filterBooks.get();
		};
		if (!on) return text;
		Pattern p = self.pattern();
		if (p == null || !p.matcher(text.getString()).find()) return text;
		MutableComponent out = Component.empty();
		text.visit((style, part) -> {
			out.append(Component.literal(p.matcher(part).replaceAll(m -> "*".repeat(m.group().length()))).withStyle(style));
			return java.util.Optional.empty();
		}, net.minecraft.network.chat.Style.EMPTY);
		return out;
	}

	private static boolean validRegex(String regex) {
		try {
			Pattern.compile(regex);
			return true;
		} catch (java.util.regex.PatternSyntaxException e) {
			return false;
		}
	}

	/** ChatComponentMixin: lines of chat kept (vanilla's 100 unless raised). */
	public static int historyLength(int vanilla) {
		ChatTweaks m = dev.coffeeclient.module.ModuleManager.ifOn(ChatTweaks.class);
		return m == null ? vanilla : Math.max(vanilla, m.historyLength.intValue());
	}

	private static final java.util.Map<net.minecraft.util.FormattedCharSequence, String> LINE_TEXT = new java.util.WeakHashMap<>();

	/** Chat graphics mixins: how far chat text moves right to make room for heads. */
	public static int headOffset() {
		ChatTweaks m = dev.coffeeclient.module.ModuleManager.ifOn(ChatTweaks.class);
		return m != null && m.chatHeads.get() ? 10 : 0;
	}

	/** The sender's face at the start of a chat line that begins with a known player's name. */
	public static void drawHead(net.minecraft.client.gui.GuiGraphicsExtractor g, int textTop, float opacity, net.minecraft.util.FormattedCharSequence line) {
		if (headOffset() == 0) return;
		var connection = mc().getConnection();
		if (connection == null) return;
		String text = LINE_TEXT.computeIfAbsent(line, l -> {
			StringBuilder b = new StringBuilder();
			l.accept((i, style, cp) -> {
				b.appendCodePoint(cp);
				return b.length() < 48;
			});
			return b.toString();
		});
		java.util.regex.Matcher m = SENDER.matcher(text);
		if (!m.find()) return;
		var info = connection.getPlayerInfo(m.group(1));
		if (info == null) return;
		net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(g, info.getSkin().body().texturePath(), 0, textTop, 8,
			info.showHat(), false, net.minecraft.util.ARGB.white(opacity));
	}

	/** HudMixin: whether leaving a server keeps the chat. */
	public static boolean keepsHistory() {
		ChatTweaks m = dev.coffeeclient.module.ModuleManager.ifOn(ChatTweaks.class);
		return m != null && m.keepHistory.get();
	}

	private Pattern pattern() {
		var terms = new java.util.ArrayList<String>();
		for (String k : keywords.get()) if (k != null && !k.isBlank()) terms.add(k.trim());
		if (useFile.get()) terms.addAll(fileWords());
		Level lvl = level.get();
		boolean whole = wholeWords.get();
		String key = terms + "|" + lvl + "|" + whole + "|" + regexKeywords.get();
		if (!key.equals(cachedKey)) {
			cachedKey = key;
			var alts = new java.util.ArrayList<String>();
			for (String t : terms) alts.add(regexKeywords.get() && validRegex(t) ? "(?:" + t + ")" : Pattern.quote(t));
			var builtIn = new java.util.ArrayList<String>();
			if (lvl != Level.NONE) {
				if (slurs == null) slurs = load("casual.txt");
				builtIn.addAll(slurs);
			}
			if (lvl == Level.FAMILY_FRIENDLY) {
				if (profanity == null) profanity = load("profanity.txt");
				builtIn.addAll(profanity);
			}
			// longest first so "motherfucker" wins over "fuck"
			builtIn.sort((a, b) -> b.length() - a.length());
			// strict mode: any word ending in "fag" (newfag, oldfag, ...), which the fixed list can't enumerate
			if (lvl == Level.FAMILY_FRIENDLY && whole) alts.add("[\\p{L}\\p{N}_]*fag(?:s|z)?");
			for (String t : builtIn) alts.add(Pattern.quote(t) + (whole && t.matches("[a-z]+") ? ENDINGS : ""));
			cached = alts.isEmpty() ? null : Pattern.compile(
				whole ? "(?<![\\p{L}\\p{N}_])(?:" + String.join("|", alts) + ")(?![\\p{L}\\p{N}_])" : String.join("|", alts),
				Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
		}
		return cached;
	}

	/** {@code clientSide}: lines the client itself generated (toggle notices etc.), which are never filtered. Most servers deliver player chat as server "system" messages, so those must be filtered too. */
	private static ChatTweaks active(boolean clientSide) {
		var modules = CoffeeClient.modules();
		if (modules == null) return null;
		ChatTweaks self = modules.get(ChatTweaks.class);
		return self != null && self.isEnabled() && !clientSide ? self : null;
	}

	/** True when the whole line should be dropped. Called from the chat mixin. */
	public static boolean shouldHide(Component message, boolean clientSide) {
		ChatTweaks self = active(clientSide);
		if (self == null) return false;
		if (self.muted(message)) return true;
		if (self.action.get() != Action.HIDE_MESSAGE) return false;
		Pattern p = self.pattern();
		return p != null && p.matcher(message.getString()).find();
	}

	private enum Kind { CHAT, JOIN_LEAVE, WHISPER, DEATH }

	static final Pattern WHISPER = Pattern.compile("^(?:\\[?\\w+ ?-> ?me\\]?|\\[me ?-> ?\\w+\\]|\\w+ whispers to you:|You whisper to \\w+:)");
	private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}");

	private static Kind kindOf(Component message, String text) {
		if (message.getContents() instanceof TranslatableContents tr) {
			String key = tr.getKey();
			if (key.startsWith("multiplayer.player.joined") || key.equals("multiplayer.player.left")) return Kind.JOIN_LEAVE;
			if (key.startsWith("death.")) return Kind.DEATH;
			if (key.startsWith("commands.message.display")) return Kind.WHISPER;
		}
		if (JOIN_LEAVE.matcher(text).matches()) return Kind.JOIN_LEAVE;
		if (WHISPER.matcher(text).find()) return Kind.WHISPER;
		return Kind.CHAT;
	}

	/** The player a chat line is from: the first argument of vanilla chat lines, else the leading name. */
	private static String senderOf(Component message, String text) {
		if (message.getContents() instanceof TranslatableContents tr && tr.getArgs().length > 0
			&& (tr.getKey().startsWith("chat.type.") || tr.getKey().startsWith("commands.message.display") || tr.getKey().startsWith("multiplayer.player."))) {
			Object arg = tr.getArgs()[0];
			String name = arg instanceof Component c ? c.getString() : String.valueOf(arg);
			if (!name.isBlank()) return name;
		}
		Matcher m = SENDER.matcher(text);
		return m.find() ? m.group(1) : null;
	}

	/** True when the line is from a muted player, or Mute all chat is on and it is not an allowed kind. */
	private boolean muted(Component message) {
		boolean listed = !mutedPlayers.get().isEmpty(), all = muteAll.get();
		if (!listed && !all) return false;
		String text = message.getString();
		String sender = senderOf(message, text);
		if (sender != null && listed && isMuted(sender)) return true;
		if (!all) return false;
		if (sender != null && allowFriends.get() && CoffeeClient.friends() != null && CoffeeClient.friends().isFriend(sender)) return false;
		return switch (kindOf(message, text)) {
			case JOIN_LEAVE -> !allowJoinLeave.get();
			case WHISPER -> !allowWhispers.get();
			case DEATH -> !allowDeaths.get();
			case CHAT -> true;
		};
	}

	/** Whether {@code name} is on the mute list, by name or by the UUID of a player on the tab list. */
	private boolean isMuted(String name) {
		var connection = Minecraft.getInstance().getConnection();
		for (String entry : mutedPlayers.get()) {
			String e = entry.trim();
			if (e.isEmpty()) continue;
			if (e.equalsIgnoreCase(name)) return true;
			if (connection != null && UUID_TEXT.matcher(e).matches()) {
				try {
					var info = connection.getPlayerInfo(UUID.fromString(e.contains("-") ? e
						: e.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5")));
					if (info != null && info.getProfile().name().equalsIgnoreCase(name)) return true;
				} catch (IllegalArgumentException ignored) {
				}
			}
		}
		return false;
	}

	/** The stack key for {@code message}, or null when stacking is off. Similar mode ignores case, digits and spacing. */
	public static String stackKey(Component message) {
		var modules = CoffeeClient.modules();
		ChatTweaks self = modules == null ? null : modules.get(ChatTweaks.class);
		if (self == null || !self.isEnabled() || !self.stackRepeats.get()) return null;
		String text = message.getString().strip();
		if (text.isEmpty()) return null;
		if (!self.similar.get()) return text;
		return WHITESPACE.matcher(DIGITS.matcher(text.toLowerCase(java.util.Locale.ROOT)).replaceAll("#")).replaceAll(" ");
	}

	private static final Pattern DIGITS = Pattern.compile("\\d+");
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	/**
	 * AntiSpam: removes the latest recent message with the same key from {@code messages} (newest
	 * first, as the chat keeps them) and returns the new message's count, or 1 when it is no repeat.
	 */
	public static int takeRepeat(List<GuiMessage> messages, String key) {
		if (key == null) return 1;
		ChatTweaks self = CoffeeClient.modules().get(ChatTweaks.class);
		int n = Math.min(messages.size(), self.lookBack.intValue());
		for (int i = 0; i < n; i++) {
			Stack stack = self.stacks.get(messages.get(i));
			if (stack != null && stack.key().equals(key)) {
				self.stacks.remove(messages.remove(i));
				return stack.count() + 1;
			}
		}
		return 1;
	}

	/** Remembers the key and count of a message just added to the chat. */
	public static void recordStack(List<GuiMessage> messages, GuiMessage message, String key, int count) {
		if (key == null) return;
		ChatTweaks self = CoffeeClient.modules().get(ChatTweaks.class);
		self.stacks.put(message, new Stack(key, count));
		if (self.stacks.size() > messages.size() + 50) {
			java.util.Set<GuiMessage> live = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
			live.addAll(messages);
			self.stacks.keySet().retainAll(live);
		}
	}

	/** {@code message} with a grey " [xN]" counter, for N over 1. */
	public static Component withCount(Component message, int count) {
		if (count <= 1) return message;
		return message.copy().append(Component.literal(" [x" + count + "]").withColor(0xAAAAAA));
	}

	/** Censors matching words in place (keeping each segment's style). Called from the chat mixin. */
	public static Component censor(Component message, boolean clientSide) {
		ChatTweaks self = active(clientSide);
		if (self == null || self.action.get() != Action.CENSOR_WORD) return message;
		Pattern p = self.pattern();
		if (p == null || !p.matcher(message.getString()).find()) return message;
		MutableComponent out = Component.empty();
		message.visit((style, text) -> {
			out.append(Component.literal(p.matcher(text).replaceAll(m -> "*".repeat(m.group().length()))).withStyle(style));
			return java.util.Optional.empty();
		}, net.minecraft.network.chat.Style.EMPTY);
		return out;
	}

	// ---------------------------------------------------------------------------------------------
	// Mentions
	// ---------------------------------------------------------------------------------------------

	static final Pattern SENDER = Pattern.compile("^\\s*(?:\\[[^\\]]*\\]\\s*)*<?([A-Za-z0-9_]{2,16})>?:?\\s");
	static final Pattern JOIN_LEAVE = Pattern.compile("^\\S+ (?:joined|left) the game$");

	private Pattern mentionCached;
	private String mentionKey = "";

	/** Your name, its parts with partial names on, and the nicknames, as one whole-word pattern (or null). */
	public static Pattern mentionPattern(String name, List<String> nicknames, boolean partial) {
		Set<String> variants = new LinkedHashSet<>();
		if (name != null && name.length() >= 2) {
			String lower = name.toLowerCase(Locale.ROOT);
			variants.add(lower);
			if (partial) {
				if (lower.length() > 4) variants.add(lower.substring(0, 4));
				for (String part : lower.split("[_\\-]+")) if (part.length() >= 3) variants.add(part);
				for (String part : name.split("(?<=[a-z])(?=[A-Z])|[_\\-]+")) if (part.length() >= 3) variants.add(part.toLowerCase(Locale.ROOT));
			}
		}
		for (String nick : nicknames) {
			for (String n : nick.split("[,\\s]+")) if (n.length() >= 2) variants.add(n.toLowerCase(Locale.ROOT));
		}
		if (variants.isEmpty()) return null;
		List<String> alts = new ArrayList<>();
		for (String v : variants) alts.add(Pattern.quote(v));
		// Longest first, so the full name is highlighted rather than just its start.
		alts.sort((a, b) -> b.length() - a.length());
		return Pattern.compile("(?<![\\p{L}\\p{N}_])@?(?:" + String.join("|", alts) + ")(?![\\p{L}\\p{N}_])",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	}

	private Pattern mentionPattern(String name) {
		String key = name + "|" + nicknames.get() + "|" + partialNames.get();
		if (!key.equals(mentionKey)) {
			mentionKey = key;
			mentionCached = mentionPattern(name, nicknames.get(), partialNames.get());
		}
		return mentionCached;
	}

	/**
	 * Highlights your name in someone else's message and plays the ping. Lines you wrote yourself and
	 * join/leave notices are skipped. Called from the chat mixin.
	 */
	public static Component mention(Component message, boolean clientSide) {
		ChatTweaks self = active(clientSide);
		if (self == null || !self.mentions.get()) return message;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return message;
		String name = mc.player.getGameProfile().name();
		Pattern p = self.mentionPattern(name);
		String text = message.getString();
		if (p == null || JOIN_LEAVE.matcher(text).matches()) return message;
		Matcher sender = SENDER.matcher(text);
		if (sender.find() && sender.group(1).equalsIgnoreCase(name)) return message;
		if (!p.matcher(text).find()) return message;

		int color = self.mentionColor.argb() & 0xFFFFFF;
		MutableComponent out = Component.empty();
		message.visit((style, segment) -> {
			Matcher m = p.matcher(segment);
			int last = 0;
			while (m.find()) {
				if (m.start() > last) out.append(Component.literal(segment.substring(last, m.start())).withStyle(style));
				out.append(Component.literal(m.group()).withStyle(style.withColor(color).withBold(true)));
				last = m.end();
			}
			if (last < segment.length()) out.append(Component.literal(segment.substring(last)).withStyle(style));
			return java.util.Optional.empty();
		}, net.minecraft.network.chat.Style.EMPTY);
		String sound = self.mentionSound.first();
		if (sound != null && self.mentionVolume.get() > 0) Sounds.play(sound, 1f, self.mentionVolume.floatValue());
		if (self.mentionToast.get()) self.notify(text.length() > 60 ? text.substring(0, 57) + "..." : text);
		return out;
	}
}
