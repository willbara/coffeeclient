package dev.coffeeclient.macro;

import java.util.List;

/**
 * One input of a macro step, trigger or condition. The editor builds the matching widget from the
 * kind, so a new step type only declares its params.
 *
 * @param options choices for {@link Kind#CHOICE}
 */
public record Param(String key, String label, Kind kind, String defaultValue, List<String> options) {
	public enum Kind {
		/** Free text (chat messages support Starscript). */
		TEXT,
		/** A number; the editor accepts digits, sign and point. */
		NUMBER,
		/** A module name. */
		MODULE,
		/** A key mapping id such as {@code key.jump}. */
		KEY_MAPPING,
		/** A raw key or mouse button (key code). */
		KEY,
		/** One of {@link #options}. */
		CHOICE,
		/** Another macro's name. */
		MACRO,
		/** A {@link MacroCondition} name; its own argument is the param named {@code arg}. */
		CONDITION
	}

	public static Param text(String key, String label, String def) {
		return new Param(key, label, Kind.TEXT, def, List.of());
	}

	public static Param number(String key, String label, String def) {
		return new Param(key, label, Kind.NUMBER, def, List.of());
	}

	public static Param choice(String key, String label, String... options) {
		return new Param(key, label, Kind.CHOICE, options[0], List.of(options));
	}

	public static Param of(String key, String label, Kind kind, String def) {
		return new Param(key, label, kind, def, List.of());
	}
}
