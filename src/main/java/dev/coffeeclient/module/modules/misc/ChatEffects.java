package dev.coffeeclient.module.modules.misc;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.Labeled;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.setting.StringSetting;
import net.minecraft.util.StringUtil;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Rewrites the chat messages you send (never commands): case, uwu, leetspeak, reversed text, a
 * Unicode font, then a prefix and suffix, in that order. Also lifts the chat box limit for long commands.
 */
public final class ChatEffects extends Module {
	public enum Case implements Labeled {
		OFF("Off"), LOWER("lowercase"), UPPER("UPPERCASE"), MOCK("mOcKiNg");

		private final String label;

		Case(String label) {
			this.label = label;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public enum Font implements Labeled {
		NORMAL("Normal", null),
		SMALL_CAPS("Small caps", "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ"),
		FULLWIDTH("Fullwidth", null),
		BUBBLE("Bubble", "ⓐⓑⓒⓓⓔⓕⓖⓗⓘⓙⓚⓛⓜⓝⓞⓟⓠⓡⓢⓣⓤⓥⓦⓧⓨⓩ");

		private final String label;
		/** Replacement for a-z (input lower-cased first), or null. */
		final String letters;

		Font(String label, String letters) {
			this.label = label;
			this.letters = letters;
		}

		@Override
		public String label() {
			return label;
		}
	}

	public final EnumSetting<Case> textCase = group(new EnumSetting<>("Case", "Change the letter case", Case.OFF), "Text");
	public final BooleanSetting uwu = group(new BooleanSetting("Uwu", "r and l become w, plus a face", false), "Text");
	public final BooleanSetting leet = group(new BooleanSetting("Leetspeak", "a→4, e→3, i→1, o→0, s→5, t→7, b→8", false), "Text");
	public final BooleanSetting reverse = group(new BooleanSetting("Reverse", "Send the message backwards", false), "Text");
	public final EnumSetting<Font> font = group(new EnumSetting<>("Font", "Unicode letters that look like another font", Font.NORMAL), "Text");
	public final StringSetting prefix = group(new StringSetting("Prefix", "Added in front of every message", "", 32), "Signature");
	public final StringSetting suffix = group(new StringSetting("Suffix", "Added after every message", "", 32), "Signature");
	public final BooleanSetting styleSignature = group(new BooleanSetting("Style signature", "Apply the effects to the prefix and suffix too", false), "Signature");

	public final BooleanSetting longCommands = group(new BooleanSetting("Long commands", "Lets the chat box take up to 32767 characters for commands. Plain messages still stop at 256 so the server does not kick you", false), "Length");

	public ChatEffects() {
		super("ChatEffects", List.of("ChatStyle", "FancyChat"), Category.MISC, "Rewrites your outgoing chat (case, leetspeak, fonts, signature).");
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	/** ChatScreenMixin: how many characters the chat box accepts. */
	public static int inputLimit(int vanilla) {
		ChatEffects m = ModuleManager.ifOn(ChatEffects.class);
		return m != null && m.longCommands.get() ? 32767 : vanilla;
	}

	/** ChatScreenMixin: whether a command is sent whole instead of cut to 256 characters. */
	public static boolean keepsLongCommand(String message) {
		return message.startsWith("/") && inputLimit(0) > 0;
	}

	/** ClientPacketListenerMixin: the chat message to send instead of {@code message}. */
	public static String rewrite(String message) {
		ChatEffects m = ModuleManager.ifOn(ChatEffects.class);
		if (m == null || message.isBlank()) return message;
		boolean style = m.styleSignature.get();
		String pre = style ? m.apply(m.prefix.get()) : m.prefix.get();
		String post = style ? m.apply(m.suffix.get()) : m.suffix.get();
		return StringUtil.trimChatMessage(StringUtil.filterText(pre + m.apply(message) + post));
	}

	private String apply(String s) {
		if (s.isEmpty()) return s;
		s = switch (textCase.get()) {
			case OFF -> s;
			case LOWER -> s.toLowerCase(Locale.ROOT);
			case UPPER -> s.toUpperCase(Locale.ROOT);
			case MOCK -> mock(s);
		};
		if (uwu.get()) s = uwu(s);
		if (leet.get()) s = leet(s);
		if (reverse.get()) s = new StringBuilder(s).reverse().toString();
		Font f = font.get();
		if (f == Font.FULLWIDTH) s = fullwidth(s);
		else if (f.letters != null) s = letters(s, f.letters);
		return s;
	}

	private static String mock(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		boolean upper = false;
		for (char c : s.toCharArray()) {
			if (Character.isLetter(c)) {
				sb.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
				upper = !upper;
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private static final String[] FACES = {" uwu", " owo", " >w<", " ^w^", " :3"};

	private static String uwu(String s) {
		String out = s.replaceAll("[rl]", "w").replaceAll("[RL]", "W")
			.replaceAll("n([aeiou])", "ny$1").replaceAll("N([aeiouAEIOU])", "Ny$1").replace("ove", "uv");
		return out + FACES[ThreadLocalRandom.current().nextInt(FACES.length)];
	}

	private static String leet(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for (char c : s.toCharArray()) {
			sb.append(switch (Character.toLowerCase(c)) {
				case 'a' -> '4';
				case 'b' -> '8';
				case 'e' -> '3';
				case 'i' -> '1';
				case 'o' -> '0';
				case 's' -> '5';
				case 't' -> '7';
				default -> c;
			});
		}
		return sb.toString();
	}

	private static String letters(String s, String table) {
		StringBuilder sb = new StringBuilder(s.length());
		for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) sb.append(c >= 'a' && c <= 'z' ? table.charAt(c - 'a') : c);
		return sb.toString();
	}

	private static String fullwidth(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for (char c : s.toCharArray()) {
			if (c == ' ') sb.append('　');
			else if (c >= '!' && c <= '~') sb.append((char) (c - '!' + 0xFF01));
			else sb.append(c);
		}
		return sb.toString();
	}

	@Override
	public String infoString() {
		return font.get() != Font.NORMAL ? font.display() : textCase.get() != Case.OFF ? textCase.display() : null;
	}
}
