package dev.coffeeclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.coffeeclient.util.KeyNames;
import dev.coffeeclient.util.LegacyKeyCodes;

/**
 * A keyboard key code, a {@link KeyNames#mouseKey(int) mouse button} code, or {@link #UNBOUND}.
 * Bindings are edited through the client's GUI widgets, which accept both keys and mouse buttons.
 */
public class KeybindSetting extends Setting<Integer> {
	public static final int UNBOUND = -1;

	/** Set while loading JSON written by the 26.3 build, whose key codes need translating. */
	private static boolean legacyCodes;

	public KeybindSetting(String name, String description, int defaultKey) {
		super(name, description, defaultKey);
	}

	/** Treat bindings read until the next call as 26.3 codes ({@link LegacyKeyCodes}). */
	public static void readingLegacyCodes(boolean legacy) {
		legacyCodes = legacy;
	}

	public int key() {
		return value;
	}

	public boolean isBound() {
		return KeyNames.isBound(value);
	}

	public void clear() {
		set(UNBOUND);
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value);
	}

	@Override
	public void fromJson(JsonElement json) {
		try {
			int key = json.getAsInt();
			set(legacyCodes ? LegacyKeyCodes.fromLegacy(key) : key);
		} catch (RuntimeException e) {
			set(defaultValue());
		}
	}

	@Override
	public String display() {
		return KeyNames.name(value);
	}
}
