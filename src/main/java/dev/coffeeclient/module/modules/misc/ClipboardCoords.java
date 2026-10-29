package dev.coffeeclient.module.modules.misc;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.EnumSetting;

import java.util.Locale;

/** Action: copies your coordinates in the chosen format (bind it to a key). */
public final class ClipboardCoords extends Module {
	public enum Format { SPACED, COMMA, BRACKETS, TELEPORT }

	public final EnumSetting<Format> format = setting(new EnumSetting<>("Format", "Text format of the copied coordinates", Format.SPACED));
	public final BooleanSetting precise = setting(new BooleanSetting("Decimals", "Exact position instead of the block", false));
	public final BooleanSetting dimension = setting(new BooleanSetting("Include dimension", "Append the dimension id", false));
	public final BooleanSetting convert = setting(new BooleanSetting("Linked dimension", "Copy the matching Nether/Overworld position", false));

	public ClipboardCoords() {
		super("ClipboardCoords", Category.MISC, "Copies your coordinates to the clipboard (runs once per press).");
	}

	@Override
	public boolean isAction() {
		return true;
	}

	@Override
	protected void onAction() {
		var mc = mc();
		if (mc.player == null || mc.level == null) return;
		double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
		String dim = mc.level.dimension().identifier().toString();
		if (convert.get()) {
			if (dim.equals("minecraft:overworld")) {
				x /= 8;
				z /= 8;
				dim = "minecraft:the_nether";
			} else if (dim.equals("minecraft:the_nether")) {
				x *= 8;
				z *= 8;
				dim = "minecraft:overworld";
			}
		}
		String[] c = precise.get()
			? new String[] {fmt(x), fmt(y), fmt(z)}
			: new String[] {Long.toString((long) Math.floor(x)), Long.toString((long) Math.floor(y)), Long.toString((long) Math.floor(z))};
		String text = switch (format.get()) {
			case SPACED -> String.join(" ", c);
			case COMMA -> String.join(", ", c);
			case BRACKETS -> "[" + String.join(", ", c) + "]";
			case TELEPORT -> "/tp @s " + String.join(" ", c);
		};
		if (dimension.get()) text += " (" + dim + ")";
		mc.keyboardHandler.setClipboard(text);
		CoffeeClient.hud().toast("Copied " + text, CoffeeClient.theme().accent);
	}

	private static String fmt(double v) {
		return String.format(Locale.ROOT, "%.2f", v);
	}
}
