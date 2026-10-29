package dev.coffeeclient.module.modules.misc;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Prefixes every chat line with the local time (applied as messages are added to the chat log). */
public final class ChatTimestamps extends Module {
	public enum Format { TWELVE_HOUR, TWENTY_FOUR_HOUR, WITH_SECONDS }
	public enum Position { AFTER_MESSAGE, BEFORE_MESSAGE }

	public final EnumSetting<Position> position = setting(new EnumSetting<>("Position", "Where the time goes on each line", Position.AFTER_MESSAGE));
	public final EnumSetting<Format> format = setting(new EnumSetting<>("Format", "Clock format", Format.TWELVE_HOUR));
	public final BooleanSetting brackets = setting(new BooleanSetting("Brackets", "Wrap the time in [ ]", true));
	public final BooleanSetting systemToo = setting(new BooleanSetting("System messages", "Also stamp server/system messages", true));
	public final ColorSetting color = setting(new ColorSetting("Color", "Timestamp colour (only the timestamp, not the message)", 0xFF8A8A8A));

	public ChatTimestamps() {
		super("ChatTimestamps", Category.MISC, "Adds a small coloured time like [3:45] to each chat line.");
	}

	/** Called from the chat mixin for every line added to the chat. */
	public static Component decorate(Component message, boolean system) {
		var modules = CoffeeClient.modules();
		if (modules == null) return message;
		ChatTimestamps self = modules.get(ChatTimestamps.class);
		if (!self.isEnabled() || (system && !self.systemToo.get())) return message;
		String pattern = switch (self.format.get()) {
			case TWELVE_HOUR -> "h:mm";
			case TWENTY_FOUR_HOUR -> "HH:mm";
			case WITH_SECONDS -> "HH:mm:ss";
		};
		String time = LocalTime.now().format(DateTimeFormatter.ofPattern(pattern));
		if (self.brackets.get()) time = "[" + time + "]";
		// the stamp is a sibling, never the parent, so its colour can't leak into the message
		MutableComponent stamp = Component.literal(time).withColor(self.color.argb() & 0xFFFFFF);
		return self.position.get() == Position.AFTER_MESSAGE
			? Component.empty().append(message).append(Component.literal(" ")).append(stamp)
			: Component.empty().append(stamp).append(Component.literal(" ")).append(message);
	}
}
