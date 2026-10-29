package dev.coffeeclient.macro;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.util.Inv;
import dev.coffeeclient.setting.Labeled;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.Locale;

/** Something a macro can wait for or stop on. {@link #argLabel} is null when it takes no argument. */
public enum MacroCondition implements Labeled {
	ON_GROUND("On ground", null),
	IN_AIR("In the air", null),
	IN_WATER("In water", null),
	SNEAKING("Sneaking", null),
	HEALTH_BELOW("Health below", "Health"),
	HEALTH_ABOVE("Health above", "Health"),
	HUNGER_BELOW("Hunger below", "Food"),
	Y_BELOW("Y below", "Y"),
	Y_ABOVE("Y above", "Y"),
	DIMENSION("In dimension", "overworld, nether or end"),
	NIGHT("It is night", null),
	RAINING("It is raining", null),
	PLAYER_NEAR("Player within", "Blocks"),
	FRIEND_NEAR("Friend within", "Blocks"),
	STRANGER_NEAR("Non-friend within", "Blocks"),
	SCREEN_OPEN("A screen is open", null),
	SCREEN_CLOSED("No screen open", null),
	HOLDING("Holding item", "Item id (part)"),
	HAS_ITEM("Has item", "Item id (part) [count]"),
	DURABILITY_BELOW("Held durability below %", "Percent"),
	INVENTORY_FULL("Inventory full", null),
	MODULE_ON("Module is on", "Module"),
	MODULE_OFF("Module is off", "Module"),
	MACRO_RUNNING("Macro running", "Macro"),
	KEY_DOWN("Key mapping held", "Key mapping"),
	CHAT_CONTAINS("Chat says", "Text");

	private final String label;
	private final String argLabel;

	MacroCondition(String label, String argLabel) {
		this.label = label;
		this.argLabel = argLabel;
	}

	@Override
	public String label() {
		return label;
	}

	public String argLabel() {
		return argLabel;
	}

	/** The editor widget for the argument. */
	public Param.Kind argKind() {
		return switch (this) {
			case MODULE_ON, MODULE_OFF -> Param.Kind.MODULE;
			case MACRO_RUNNING -> Param.Kind.MACRO;
			case KEY_DOWN -> Param.Kind.KEY_MAPPING;
			case HEALTH_BELOW, HEALTH_ABOVE, HUNGER_BELOW, Y_BELOW, Y_ABOVE, PLAYER_NEAR, FRIEND_NEAR, STRANGER_NEAR, DURABILITY_BELOW -> Param.Kind.NUMBER;
			default -> Param.Kind.TEXT;
		};
	}

	public static MacroCondition parse(String name) {
		try {
			return valueOf(name);
		} catch (RuntimeException e) {
			return ON_GROUND;
		}
	}

	/** @param chat lower-cased chat lines that arrived since the macro's previous step finished */
	public boolean test(String arg, Collection<String> chat) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		return switch (this) {
			case ON_GROUND -> p != null && p.onGround();
			case IN_AIR -> p != null && !p.onGround();
			case IN_WATER -> p != null && p.isInWater();
			case SNEAKING -> p != null && p.isShiftKeyDown();
			case HEALTH_BELOW -> p != null && p.getHealth() < number(arg);
			case HEALTH_ABOVE -> p != null && p.getHealth() > number(arg);
			case HUNGER_BELOW -> p != null && p.getFoodData().getFoodLevel() < number(arg);
			case Y_BELOW -> p != null && p.getY() < number(arg);
			case Y_ABOVE -> p != null && p.getY() > number(arg);
			case DIMENSION -> mc.level != null && !arg.isBlank()
				&& mc.level.dimension().identifier().getPath().contains(arg.trim().toLowerCase(Locale.ROOT));
			case NIGHT -> mc.level != null && mc.level.isDarkOutside();
			case RAINING -> mc.level != null && mc.level.isRaining();
			case PLAYER_NEAR, FRIEND_NEAR, STRANGER_NEAR -> playerNear(mc, number(arg));
			case SCREEN_OPEN -> mc.gui.screen() != null;
			case SCREEN_CLOSED -> mc.gui.screen() == null;
			case HOLDING -> p != null && isItem(p.getMainHandItem(), arg);
			case HAS_ITEM -> p != null && hasItem(arg);
			case DURABILITY_BELOW -> {
				ItemStack held = p == null ? ItemStack.EMPTY : p.getMainHandItem();
				yield held.isDamageableItem() && (held.getMaxDamage() - held.getDamageValue()) * 100.0 / held.getMaxDamage() < number(arg);
			}
			case INVENTORY_FULL -> p != null && p.getInventory().getFreeSlot() < 0;
			case MODULE_ON -> CoffeeClient.modules().byName(arg).map(m -> m.isEnabled()).orElse(false);
			case MODULE_OFF -> !CoffeeClient.modules().byName(arg).map(m -> m.isEnabled()).orElse(false);
			case MACRO_RUNNING -> {
				Macro macro = CoffeeClient.macros().byName(arg);
				yield macro != null && CoffeeClient.macros().isRunning(macro);
			}
			case KEY_DOWN -> {
				KeyMapping key = KeyMapping.get(arg);
				yield key != null && key.isDown();
			}
			case CHAT_CONTAINS -> {
				String needle = arg.trim().toLowerCase(Locale.ROOT);
				yield !needle.isEmpty() && chat.stream().anyMatch(line -> line.contains(needle));
			}
		};
	}

	/** Another player within {@code range} blocks; friends only or non-friends only for those conditions. */
	private boolean playerNear(Minecraft mc, double range) {
		if (mc.player == null || mc.level == null) return false;
		for (Player other : mc.level.players()) {
			if (other == mc.player || mc.player.distanceTo(other) > range) continue;
			if (this == PLAYER_NEAR || CoffeeClient.friends().isFriend(other.getName().getString()) == (this == FRIEND_NEAR)) return true;
		}
		return false;
	}

	/** {@code arg} is an item id part, optionally followed by the least count (default 1). */
	private static boolean hasItem(String arg) {
		String text = arg.trim();
		int space = text.lastIndexOf(' ');
		double need = 1;
		if (space > 0 && text.substring(space + 1).matches("\\d+")) {
			need = number(text.substring(space + 1));
			text = text.substring(0, space);
		}
		String id = text;
		return !id.isBlank() && Inv.find(stack -> isItem(stack, id)).count() >= need;
	}

	/** Whether the stack's item id contains {@code part} (ignoring case). */
	static boolean isItem(ItemStack stack, String part) {
		return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().contains(part.trim().toLowerCase(Locale.ROOT));
	}

	static double number(String s) {
		try {
			return Double.parseDouble(s.trim());
		} catch (RuntimeException e) {
			return 0;
		}
	}
}
