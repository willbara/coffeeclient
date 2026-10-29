package dev.coffeeclient.module.modules.client;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.setting.StringSetting;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.security.SecureRandom;

/**
 * Hides identifying info in the client's own UI (HUD, dashboard) and on the vanilla F3 screen, and can
 * shuffle block texture rotations and flower offsets so screenshots and streams can't be matched to
 * coordinates by them. It can also mask what you type after /login or
 * /register. Word filtering lives in ChatTweaks.
 */
public final class StreamerMode extends Module {
	public enum Rotation { OFF, RANDOM, CUSTOM_SEED, NONE }

	public final BooleanSetting hideCoordinates = setting(new BooleanSetting("Hide coordinates", "Blank out coordinates in the HUD, dashboard and F3 screen", true));
	public final BooleanSetting hideServer = setting(new BooleanSetting("Hide server", "Blank out the server address", true));
	public final BooleanSetting hideSeed = setting(new BooleanSetting("Hide seed", "Blank out the world seed on the dashboard", true));

	public final BooleanSetting maskLogin = group(new BooleanSetting("Mask passwords", "Show stars for what you type after a login command", true), "Passwords");
	public final StringSetting maskPattern = group(new StringSetting("Command pattern", "Regex for the part kept visible; everything after it is masked",
		"^/(login|log|l|register|reg|changepassword|changepass)\\s+", 200), "Passwords");

	public final EnumSetting<Rotation> textureRotation = group(new EnumSetting<>("Texture rotation",
		"Random: a new shuffle each time. Custom seed: the same shuffle every time. None: every block the same way", Rotation.OFF), "Textures");
	public final StringSetting seedText = group(new StringSetting("Seed", "Any text or number", "coffee", 32), "Textures");
	public final BooleanSetting keepDripstoneBamboo = group(new BooleanSetting("Keep dripstone and bamboo",
		"Leave these alone: their offset also moves their hitbox", true), "Textures");

	/** Mixed into every block's texture seed; the offsets shift flower and grass positions. */
	private volatile long seed;
	private volatile int offsetX, offsetZ;
	/** Modules restored on startup skip onEnable, so the first block asks for a shuffle. */
	private volatile boolean shuffled;
	private static final SecureRandom RANDOM = new SecureRandom();

	public StreamerMode() {
		super("StreamerMode", Category.CLIENT, "Hides coordinates, server address and seed in the client's UI.");
		seedText.visibleWhen(() -> textureRotation.get() == Rotation.CUSTOM_SEED);
		keepDripstoneBamboo.visibleWhen(() -> textureRotation.get() != Rotation.OFF);
		maskPattern.visibleWhen(maskLogin::get);
		for (Setting<?> s : java.util.List.of(textureRotation, seedText, keepDripstoneBamboo)) s.onChange(v -> reshuffle());
	}

	@Override
	protected void onEnable() {
		reshuffle();
	}

	@Override
	protected void onDisable() {
		rebuildChunks();
	}

	private void reshuffle() {
		long next = switch (textureRotation.get()) {
			case RANDOM -> RANDOM.nextLong();
			case CUSTOM_SEED -> parse(seedText.get());
			default -> 0;
		};
		seed = next;
		offsetX = (int) (next & 0xFFFF) - 0x8000;
		offsetZ = (int) ((next >>> 16) & 0xFFFF) - 0x8000;
		shuffled = true;
		if (isEnabled()) rebuildChunks();
	}

	private static long parse(String text) {
		try {
			return Long.parseLong(text.trim());
		} catch (NumberFormatException e) {
			long hash = 1125899906842597L;
			for (byte b : text.getBytes(StandardCharsets.UTF_8)) hash = 31 * hash + b;
			return hash;
		}
	}

	private void rebuildChunks() {
		var mc = mc();
		if (mc.level != null && mc.levelExtractor != null) mc.execute(mc.levelExtractor::allChanged);
	}

	/** The rotation setting in effect for this block, or OFF. */
	private static Rotation rotation(BlockState state) {
		if (!ModuleManager.on(StreamerMode.class)) return Rotation.OFF;
		StreamerMode self = self();
		Rotation r = self.textureRotation.get();
		if (r == Rotation.OFF || state.is(Blocks.POTENT_SULFUR)) return Rotation.OFF;
		if (!self.shuffled) self.reshuffle();
		if (self.keepDripstoneBamboo.get() && (state.is(Blocks.POINTED_DRIPSTONE) || state.is(Blocks.BAMBOO))) return Rotation.OFF;
		return r;
	}

	/** The texture seed to use for a block, or null for vanilla's. Called from the block mixin (render threads). */
	public static Long textureSeed(BlockState state, BlockPos pos) {
		return switch (rotation(state)) {
			case OFF -> null;
			case NONE -> 42L;
			default -> Mth.getSeed(pos.getX(), pos.getY(), pos.getZ()) ^ self().seed;
		};
	}

	/** True when a block's random offset (flowers, grass) should be dropped. */
	public static boolean noOffset(BlockState state) {
		return rotation(state) == Rotation.NONE;
	}

	/** The position a block's random offset is worked out from. */
	public static BlockPos offsetPos(BlockState state, BlockPos pos) {
		Rotation r = rotation(state);
		if (r == Rotation.OFF || r == Rotation.NONE) return pos;
		StreamerMode self = self();
		return pos.offset(self.offsetX, 0, self.offsetZ);
	}

	private static StreamerMode self() {
		return CoffeeClient.modules().get(StreamerMode.class);
	}

	/** ChatScreenMixin: the chat box text shown with the password part as stars, or null to draw it as usual. */
	public static FormattedCharSequence maskInput(String value, String segment, int offset) {
		StreamerMode m = ModuleManager.ifOn(StreamerMode.class);
		if (m == null || !m.maskLogin.get()) return null;
		int from = maskFrom(value, m.maskPattern.get());
		if (from < 0) return null;
		StringBuilder shown = new StringBuilder(segment);
		for (int i = 0; i < shown.length(); i++) if (offset + i >= from) shown.setCharAt(i, '*');
		return FormattedCharSequence.forward(shown.toString(), Style.EMPTY);
	}

	/** Where masking starts in {@code value} (the end of the pattern's match), or -1. */
	static int maskFrom(String value, String pattern) {
		try {
			Matcher m = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(value);
			return m.find() && m.start() == 0 ? m.end() : -1;
		} catch (PatternSyntaxException e) {
			return -1;
		}
	}

	public static boolean hideCoords() {
		return ModuleManager.on(StreamerMode.class) && self().hideCoordinates.get();
	}

	public static boolean hideServer() {
		return ModuleManager.on(StreamerMode.class) && self().hideServer.get();
	}

	public static boolean hideSeed() {
		return ModuleManager.on(StreamerMode.class) && self().hideSeed.get();
	}

	/** Replacement text for hidden values. */
	public static final String HIDDEN = "(hidden)";

	/** Vanilla F3 line prefixes whose values reveal the player or chunk position. */
	private static final String[] COORD_LINE_PREFIXES = {
		"XYZ: ",
		"Block: ",
		"Chunk: ",
		"Section-relative: ",
		"Targeted Block: ",
		"Targeted Fluid: "
	};

	/** Keeps a vanilla F3 line's label but replaces its coordinate value with {@link #HIDDEN}. */
	public static String maskCoordinates(String line) {
		for (String prefix : COORD_LINE_PREFIXES) {
			int index = line.indexOf(prefix);
			if (index >= 0) return line.substring(0, index + prefix.length()) + HIDDEN;
		}
		return line;
	}
}
