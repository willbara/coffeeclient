package dev.coffeeclient.render;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.properties.WoodType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Signs near the player for BlockESP: their text, a guess at whether they might be old, and a
 * memory of what each one said, so a sign that changes or disappears can be reported.
 */
public final class SignMarks {
	public record Mark(BlockPos pos, String text, boolean maybeOld) {
	}

	private static final Pattern RECENT_YEAR = Pattern.compile("\\b20(1[6-9]|[2-9]\\d)\\b");

	/** What each sign said when last seen. */
	private final Map<BlockPos, String> known = new HashMap<>();

	public void clear() {
		known.clear();
	}

	/** Signs among {@code signs}, with history events for those within {@code inRange} that changed or went missing. */
	public List<Mark> update(List<SignBlockEntity> signs, java.util.function.Predicate<BlockPos> inRange, BiConsumer<BlockPos, String> changed, BiConsumer<BlockPos, String> gone) {
		List<Mark> out = new ArrayList<>();
		Map<BlockPos, String> seen = new HashMap<>();
		for (SignBlockEntity sign : signs) {
			String back = text(sign.getBackText());
			String text = text(sign.getFrontText()) + (back.isEmpty() ? "" : " / " + back);
			if (text.isBlank()) continue;
			BlockPos pos = sign.getBlockPos().immutable();
			seen.put(pos, text);
			String before = known.get(pos);
			if (before != null && !before.equals(text)) changed.accept(pos, before + " -> " + text);
			out.add(new Mark(pos, text, maybeOld(sign)));
		}
		known.entrySet().removeIf(e -> {
			if (seen.containsKey(e.getKey()) || !inRange.test(e.getKey())) return false;
			gone.accept(e.getKey(), e.getValue());
			return true;
		});
		known.putAll(seen);
		return out;
	}

	private static String text(SignText side) {
		StringBuilder b = new StringBuilder();
		for (Component line : side.getMessages(false)) {
			String s = line.getString().trim();
			if (s.isEmpty()) continue;
			if (b.length() > 0) b.append(" | ");
			b.append(s);
		}
		return b.toString();
	}

	/**
	 * Old signs could only be oak, one-sided, black, unglowing and unwaxed; a year after 2015 in
	 * the text rules one out.
	 */
	// shortcut: style and side checks only, not the stored text format older servers left; upgrade if it flags too many new signs
	private static boolean maybeOld(SignBlockEntity sign) {
		var b = sign.getBlockState().getBlock();
		if (!(b instanceof SignBlock block) || block.type() != WoodType.OAK
			|| b instanceof net.minecraft.world.level.block.CeilingHangingSignBlock || b instanceof net.minecraft.world.level.block.WallHangingSignBlock) return false;
		SignText front = sign.getFrontText();
		if (sign.isWaxed() || front.hasGlowingText() || front.getColor() != DyeColor.BLACK || !text(sign.getBackText()).isEmpty()) return false;
		return !RECENT_YEAR.matcher(text(front)).find();
	}
}
