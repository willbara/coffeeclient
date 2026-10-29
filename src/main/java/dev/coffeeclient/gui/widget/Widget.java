package dev.coffeeclient.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.UiState;

import java.util.List;
import java.util.function.Supplier;

/**
 * Base of the client's widget library. Widgets are positioned by their owner (via {@link #setBounds})
 * and draw themselves through {@link Ui}. Mouse coordinates are GUI-scaled.
 */
public abstract class Widget {
	/**
	 * Mouse button codes as delivered by 26.2's input API: GLFW's 0-based codes (left = 0, right = 1,
	 * middle = 2).
	 */
	public static final int LEFT = InputConstants.MOUSE_BUTTON_LEFT;
	public static final int MIDDLE = InputConstants.MOUSE_BUTTON_MIDDLE;
	public static final int RIGHT = InputConstants.MOUSE_BUTTON_RIGHT;

	public int x;
	public int y;
	public int width;
	public int height;
	public boolean visible = true;
	/** 0..1, eased toward 1 while hovered. */
	protected float hover;
	private Supplier<List<String>> tooltip;

	public Widget setBounds(int x, int y, int width, int height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		return this;
	}

	public Widget tooltip(Supplier<List<String>> tooltip) {
		this.tooltip = tooltip;
		return this;
	}

	public Widget tooltip(String... lines) {
		List<String> l = List.of(lines);
		this.tooltip = () -> l;
		return this;
	}

	public boolean contains(double mx, double my) {
		return mx >= x && mx < x + width && my >= y && my < y + height;
	}

	public boolean isHovered(Ui ui) {
		return ui.hoverAllowed && contains(ui.mouseX, ui.mouseY);
	}

	public final void render(Ui ui) {
		if (!visible) return;
		boolean hovered = isHovered(ui);
		hover += ((hovered ? 1f : 0f) - hover) * ui.animStep(14f);
		draw(ui);
		// children draw first, so a hovered child's tooltip wins over its container's; short hover delay
		if (hovered && tooltip != null && hover > 0.9f && ui.pendingTooltip() == null && tooltipAllowed(ui)) ui.tooltip(tooltip.get());
	}

	/** Lets a widget suppress its own tooltip over parts of itself (e.g. a row's control). */
	protected boolean tooltipAllowed(Ui ui) {
		return true;
	}

	protected abstract void draw(Ui ui);

	public boolean mouseClicked(double mx, double my, int button) {
		return false;
	}

	/**
	 * While this widget is listening for a binding (see {@link #isFocused()}), it may claim a mouse
	 * button as the new binding. Return true to consume the click.
	 */
	public boolean mouseBinding(double mx, double my, int button) {
		return false;
	}

	public boolean mouseReleased(double mx, double my, int button) {
		return false;
	}

	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		return false;
	}

	public boolean mouseScrolled(double mx, double my, double amount) {
		return false;
	}

	public boolean keyPressed(int key, int scancode, int modifiers) {
		return false;
	}

	public boolean charTyped(String chars) {
		return false;
	}

	public boolean isFocused() {
		return UiState.isFocused(this);
	}

	/** Called when another widget takes focus or the screen closes. */
	public void onFocusLost() {
	}
}
