package dev.coffeeclient.util;

import com.mojang.blaze3d.platform.InputConstants;
import dev.coffeeclient.setting.KeybindSetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

/**
 * Display names and code spaces for keyboard keys, mouse buttons and key mappings.
 *
 * <p>A stored binding is either a positive keyboard key code, one of the negative
 * {@link #mouseKey(int) mouse button} codes, or {@link KeybindSetting#UNBOUND}.
 */
public final class KeyNames {
	/**
	 * Mouse buttons are stored as {@code MOUSE_BASE - 1 - button}, where the button uses this version's
	 * GLFW numbering (0 left, 1 right, 2 middle, 3+ the extra buttons) as seen in
	 * {@code InputConstants.MOUSE_BUTTON_*}. Negative so a stored mouse code can never clash with a
	 * keyboard key code.
	 */
	public static final int MOUSE_BASE = -100;

	private KeyNames() {
	}

	/** The stored code for a mouse button. */
	public static int mouseKey(int button) {
		return MOUSE_BASE - 1 - button;
	}

	/** True when a stored code (or raw event code) is a mouse button. */
	public static boolean isMouse(int key) {
		return key < MOUSE_BASE;
	}

	/** The mouse button a stored code refers to. */
	public static int mouseButton(int key) {
		return MOUSE_BASE - 1 - key;
	}

	/** True when a stored code is a real binding (a keyboard key or a mouse button). */
	public static boolean isBound(int key) {
		return isMouse(key) || key > 0;
	}

	public static String name(int key) {
		if (isMouse(key)) {
			try {
				return InputConstants.Type.MOUSE.getOrCreate(mouseButton(key)).getDisplayName().getString();
			} catch (RuntimeException e) {
				return "Mouse " + mouseButton(key);
			}
		}
		if (key <= 0) return "Unbound";
		try {
			return InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
		} catch (RuntimeException e) {
			return "Key " + key;
		}
	}

	public static String name(KeyMapping mapping) {
		if (mapping == null || mapping.isUnbound()) return "Unbound";
		return mapping.getTranslatedKeyMessage().getString();
	}

	/** Whether the keyboard key behind {@code mapping} is held right now (mouse-bound mappings report false). */
	public static boolean isPhysicallyDown(KeyMapping mapping) {
		int key = boundKey(mapping);
		return isKeyDown(key);
	}

	/** Whether keyboard key {@code key} is held right now. */
	public static boolean isKeyDown(int key) {
		return key > 0 && InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), key);
	}

	/** Whether the binding {@code key} (keyboard key or mouse button) is held right now. */
	public static boolean isHeld(int key) {
		if (isMouse(key)) {
			MouseHandler mouse = Minecraft.getInstance().mouseHandler;
			return switch (mouseButton(key)) {
				case InputConstants.MOUSE_BUTTON_LEFT -> mouse.isLeftPressed();
				case InputConstants.MOUSE_BUTTON_MIDDLE -> mouse.isMiddlePressed();
				case InputConstants.MOUSE_BUTTON_RIGHT -> mouse.isRightPressed();
				default -> false;
			};
		}
		return isKeyDown(key);
	}

	/** The stored code of a vanilla mapping (keyboard key or mouse button), or {@link KeybindSetting#UNBOUND}. */
	public static int boundKey(KeyMapping mapping) {
		if (mapping == null || mapping.isUnbound()) return KeybindSetting.UNBOUND;
		InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(mapping);
		if (key.getType() == InputConstants.Type.MOUSE) return mouseKey(key.getValue());
		return key.getType() == InputConstants.Type.KEYSYM ? key.getValue() : KeybindSetting.UNBOUND;
	}

	/** The vanilla key a stored code maps to; unknown for anything that is not a real binding. */
	public static InputConstants.Key toKey(int key) {
		if (isMouse(key)) return InputConstants.Type.MOUSE.getOrCreate(mouseButton(key));
		return isBound(key) ? InputConstants.Type.KEYSYM.getOrCreate(key) : InputConstants.UNKNOWN;
	}
}
