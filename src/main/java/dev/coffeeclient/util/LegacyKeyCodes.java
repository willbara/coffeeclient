package dev.coffeeclient.util;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

/**
 * Translates bindings saved by the 26.3 build, whose input layer uses SDL scancodes (A = 4) and 1-based
 * mouse buttons (1 left, 2 middle, 3 right), into this version's GLFW codes (A = 65; 0 left, 1 right,
 * 2 middle). Configs and macros written by 26.3 are converted once as they load.
 */
public final class LegacyKeyCodes {
	/** Pairs of (26.3 code, 26.2 code), matched by vanilla's {@code InputConstants.KEY_*} names. */
	private static final int[] PAIRS = {
		4, 65, 5, 66, 6, 67, 7, 68, 8, 69, 9, 70, 10, 71, 11, 72, 12, 73, 13, 74, 14, 75, 15, 76, 16, 77,
		17, 78, 18, 79, 19, 80, 20, 81, 21, 82, 22, 83, 23, 84, 24, 85, 25, 86, 26, 87, 27, 88, 28, 89, 29,
		90, 30, 49, 31, 50, 32, 51, 33, 52, 34, 53, 35, 54, 36, 55, 37, 56, 38, 57, 39, 48, 40, 257, 41,
		256, 42, 259, 43, 258, 44, 32, 45, 45, 46, 61, 47, 91, 48, 93, 49, 92, 51, 59, 52, 39, 53, 96, 54,
		44, 55, 46, 56, 47, 57, 280, 58, 290, 59, 291, 60, 292, 61, 293, 62, 294, 63, 295, 64, 296, 65,
		297, 66, 298, 67, 299, 68, 300, 69, 301, 70, 283, 71, 281, 72, 284, 73, 260, 74, 268, 75, 266, 76,
		261, 77, 269, 78, 267, 79, 262, 80, 263, 81, 264, 82, 265, 83, 282, 85, 332, 87, 334, 88, 335, 89,
		321, 90, 322, 91, 323, 92, 324, 93, 325, 94, 326, 95, 327, 96, 328, 97, 329, 98, 320, 103, 336,
		104, 302, 105, 303, 106, 304, 107, 305, 108, 306, 109, 307, 110, 308, 111, 309, 112, 310, 113, 311,
		114, 312, 115, 313, 220, 330, 224, 341, 225, 340, 226, 342, 227, 343, 228, 345, 229, 344, 230, 346,
		231, 347
	};
	private static final Int2IntOpenHashMap SDL_TO_GLFW = new Int2IntOpenHashMap(PAIRS.length / 2);

	static {
		for (int i = 0; i < PAIRS.length; i += 2) SDL_TO_GLFW.put(PAIRS[i], PAIRS[i + 1]);
	}

	private LegacyKeyCodes() {
	}

	/** The stored binding {@code code} (26.3 encoding) in this version's encoding. */
	public static int fromLegacy(int code) {
		if (code < KeyNames.MOUSE_BASE) {
			int sdlButton = KeyNames.MOUSE_BASE - code;
			int glfwButton = switch (sdlButton) {
				case 1 -> 0;
				case 2 -> 2;
				case 3 -> 1;
				default -> sdlButton - 1;
			};
			return KeyNames.mouseKey(glfwButton);
		}
		if (code <= 0) return code;
		return SDL_TO_GLFW.getOrDefault(code, code);
	}
}
