package dev.coffeeclient.gui;

import dev.coffeeclient.gui.widget.Widget;

/**
 * Focus and overlay state for the currently open client screen. Only one client screen is open at a
 * time, so this is global; {@link CoffeeScreen} resets it when it opens/closes.
 */
public final class UiState {
	private static Widget focused;
	private static Widget overlay;
	private static int screenWidth = 320;
	private static int screenHeight = 200;

	private UiState() {
	}

	public static void focus(Widget w) {
		if (focused != null && focused != w) focused.onFocusLost();
		focused = w;
	}

	public static void clearFocus() {
		focus(null);
	}

	public static boolean isFocused(Widget w) {
		return focused == w;
	}

	public static Widget focused() {
		return focused;
	}

	/** Show a widget above everything else (dropdown list, context menu). Clicking outside closes it. */
	public static void openOverlay(Widget w) {
		overlay = w;
	}

	public static void closeOverlay() {
		overlay = null;
	}

	public static Widget overlay() {
		return overlay;
	}

	/** Layout size of the open client screen (for placing popups). */
	public static void setScreenSize(int width, int height) {
		screenWidth = width;
		screenHeight = height;
	}

	public static int screenWidth() {
		return screenWidth;
	}

	public static int screenHeight() {
		return screenHeight;
	}

	public static void reset() {
		clearFocus();
		overlay = null;
	}
}
