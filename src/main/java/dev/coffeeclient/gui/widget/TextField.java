package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.UiState;
import dev.coffeeclient.gui.theme.Theme;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;

import java.util.function.Consumer;
import java.util.function.Predicate;

/** Single-line text input with cursor, select-all, clipboard and an optional character filter. */
public class TextField extends Widget {
	private String text = "";
	private int cursor;
	private boolean allSelected;
	private int scroll;
	private int maxLength = 64;
	private String placeholder = "";
	private Predicate<String> filter = s -> true;
	private Consumer<String> onChange = s -> {
	};
	private Consumer<String> onSubmit;
	private boolean centered;
	private boolean masked;

	public TextField text(String value) {
		this.text = value == null ? "" : value;
		this.cursor = this.text.length();
		this.allSelected = false;
		return this;
	}

	public String text() {
		return text;
	}

	public TextField maxLength(int max) {
		this.maxLength = max;
		return this;
	}

	public TextField placeholder(String placeholder) {
		this.placeholder = placeholder;
		return this;
	}

	/** Reject edits whose result fails the predicate (e.g. digits only). */
	public TextField filter(Predicate<String> filter) {
		this.filter = filter;
		return this;
	}

	public TextField onChange(Consumer<String> onChange) {
		this.onChange = onChange;
		return this;
	}

	public TextField onSubmit(Consumer<String> onSubmit) {
		this.onSubmit = onSubmit;
		return this;
	}

	public TextField centered() {
		this.centered = true;
		return this;
	}

	/** Show the text as asterisks (passwords); copying it is blocked. */
	public TextField masked() {
		this.masked = true;
		return this;
	}

	/** The text as drawn. */
	private String shown() {
		return masked ? "*".repeat(text.length()) : text;
	}

	public void focus() {
		UiState.focus(this);
		cursor = text.length();
	}

	@Override
	protected void draw(Ui ui) {
		Theme t = ui.theme;
		boolean focused = isFocused();
		Draw.box(ui.g, x, y, width, height, focused ? t.controlHover : t.control, focused ? t.accent : hover > 0.5f ? t.text : t.border);
		int innerW = width - 8;
		int ty = y + (height - 8) / 2;
		if (text.isEmpty() && !focused) {
			Draw.text(ui.g, Draw.trim(placeholder, innerW), x + 4, ty, t.dimText);
			return;
		}
		// keep the cursor in view
		String shown = shown();
		int cursorPx = Draw.width(shown.substring(0, cursor));
		if (cursorPx - scroll > innerW) scroll = cursorPx - innerW;
		if (cursorPx - scroll < 0) scroll = cursorPx;
		int textW = Draw.width(shown);
		int startX = centered && textW < innerW ? x + 4 + (innerW - textW) / 2 : x + 4 - scroll;
		ui.g.enableScissor(x + 2, y + 1, x + width - 2, y + height - 1);
		if (allSelected && !text.isEmpty()) Draw.rect(ui.g, startX - 1, ty - 1, textW + 2, 10, t.accent(0x60));
		Draw.text(ui.g, shown, startX, ty, t.text);
		if (focused && (ui.timeMs / 500) % 2 == 0) {
			Draw.rect(ui.g, startX + cursorPx, ty - 1, 1, 10, t.accent);
		}
		ui.g.disableScissor();
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button == RIGHT) {
			set("");
		}
		UiState.focus(this);
		// place cursor by click position
		int rel = (int) mx - (x + 4) + scroll;
		cursor = Draw.substringByWidth(text, Math.max(0, rel)).length();
		allSelected = false;
		return true;
	}

	@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		if (!isFocused()) return false;
		boolean ctrl = (modifiers & InputConstants.MOD_CONTROL) != 0 || (modifiers & InputConstants.MOD_SUPER) != 0;
		switch (key) {
			case InputConstants.KEY_ESCAPE -> UiState.clearFocus();
			case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
				if (onSubmit != null) onSubmit.accept(text);
				UiState.clearFocus();
			}
			case InputConstants.KEY_BACKSPACE -> {
				if (allSelected) set("");
				else if (cursor > 0) {
					int from = ctrl ? previousWord() : cursor - 1;
					edit(text.substring(0, from) + text.substring(cursor), from);
				}
			}
			case InputConstants.KEY_DELETE -> {
				if (allSelected) set("");
				else if (cursor < text.length()) edit(text.substring(0, cursor) + text.substring(cursor + 1), cursor);
			}
			case InputConstants.KEY_LEFT -> move(ctrl ? previousWord() : cursor - 1);
			case InputConstants.KEY_RIGHT -> move(cursor + 1);
			case InputConstants.KEY_HOME -> move(0);
			case InputConstants.KEY_END -> move(text.length());
			case InputConstants.KEY_A -> {
				if (!ctrl) return true;
				allSelected = true;
			}
			case InputConstants.KEY_C -> {
				if (ctrl && !masked) Minecraft.getInstance().keyboardHandler.setClipboard(text);
			}
			case InputConstants.KEY_V -> {
				if (ctrl) insert(Minecraft.getInstance().keyboardHandler.getClipboard().replaceAll("[\\r\\n\\t]", ""));
			}
			default -> {
				// printable keys arrive through charTyped; do not consume them here
			return false;
			}
		}
		return true;
	}

	@Override
	public boolean charTyped(String chars) {
		if (!isFocused()) return false;
		insert(chars);
		return true;
	}

	private int previousWord() {
		int i = cursor - 1;
		while (i > 0 && text.charAt(i - 1) == ' ') i--;
		while (i > 0 && text.charAt(i - 1) != ' ') i--;
		return Math.max(0, i);
	}

	private void move(int pos) {
		cursor = Math.max(0, Math.min(text.length(), pos));
		allSelected = false;
	}

	private void insert(String s) {
		String base = allSelected ? "" : text;
		int at = allSelected ? 0 : cursor;
		String next = base.substring(0, at) + s + base.substring(at);
		if (next.length() > maxLength) return;
		edit(next, at + s.length());
	}

	private void set(String s) {
		edit(s, s.length());
	}

	private void edit(String next, int newCursor) {
		if (!filter.test(next)) return;
		text = next;
		cursor = Math.max(0, Math.min(text.length(), newCursor));
		allSelected = false;
		onChange.accept(text);
	}

	@Override
	public void onFocusLost() {
		allSelected = false;
	}
}
