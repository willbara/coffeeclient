package dev.coffeeclient.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.UiState;
import dev.coffeeclient.gui.theme.Theme;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/** Multi-line text input: wraps at the box width, Enter starts a new line, Up and Down move by visual line. */
public class TextArea extends Widget {
	private static final int LINE = 10;
	private String text = "";
	private int cursor;
	private int scroll;
	private int maxLength = 1000;
	private String placeholder = "";
	private Consumer<String> onBlur = s -> {
	};

	public TextArea text(String value) {
		this.text = value == null ? "" : value;
		this.cursor = this.text.length();
		return this;
	}

	public TextArea maxLength(int max) {
		this.maxLength = max;
		return this;
	}

	public TextArea placeholder(String placeholder) {
		this.placeholder = placeholder;
		return this;
	}

	/** Called with the final text when the box loses focus (the owner saves here, not on every key). */
	public TextArea onBlur(Consumer<String> onBlur) {
		this.onBlur = onBlur;
		return this;
	}

	/**
	 * Index where each visual line starts. A line ends at a newline or where the next character would
	 * overflow {@code maxWidth}; a newline belongs to the line before it.
	 */
	static List<Integer> lineStarts(String text, int maxWidth, ToIntFunction<String> measure) {
		List<Integer> starts = new ArrayList<>(List.of(0));
		int start = 0;
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) == '\n') {
				starts.add(i + 1);
				start = i + 1;
			} else if (i > start && measure.applyAsInt(text.substring(start, i + 1)) > maxWidth) {
				starts.add(i);
				start = i;
			}
		}
		return starts;
	}

	private List<Integer> lines() {
		return lineStarts(text, width - 8, Draw::width);
	}

	/** The visual line holding {@code pos}. */
	private static int lineOf(List<Integer> starts, int pos) {
		int line = 0;
		for (int i = 1; i < starts.size() && starts.get(i) <= pos; i++) line = i;
		return line;
	}

	/** End of a visual line, not counting its newline. */
	private int lineEnd(List<Integer> starts, int line) {
		if (line + 1 >= starts.size()) return text.length();
		int next = starts.get(line + 1);
		return text.charAt(next - 1) == '\n' ? next - 1 : next;
	}

	@Override
	protected void draw(Ui ui) {
		Theme t = ui.theme;
		boolean focused = isFocused();
		Draw.box(ui.g, x, y, width, height, focused ? t.controlHover : t.control, focused ? t.accent : hover > 0.5f ? t.text : t.border);
		if (text.isEmpty() && !focused) {
			Draw.text(ui.g, Draw.trim(placeholder, width - 8), x + 4, y + 3, t.dimText);
			return;
		}
		List<Integer> starts = lines();
		int current = lineOf(starts, cursor);
		int visible = Math.max(1, (height - 6) / LINE);
		if (current < scroll) scroll = current;
		if (current >= scroll + visible) scroll = current - visible + 1;
		ui.g.enableScissor(x + 2, y + 1, x + width - 2, y + height - 1);
		for (int line = scroll; line < starts.size() && line < scroll + visible; line++) {
			int start = starts.get(line);
			int ly = y + 3 + (line - scroll) * LINE;
			Draw.text(ui.g, text.substring(start, lineEnd(starts, line)), x + 4, ly, t.text);
			if (focused && line == current && (ui.timeMs / 500) % 2 == 0) {
				Draw.rect(ui.g, x + 4 + Draw.width(text.substring(start, cursor)), ly - 1, 1, 10, t.accent);
			}
		}
		ui.g.disableScissor();
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		UiState.focus(this);
		List<Integer> starts = lines();
		int line = Math.clamp(scroll + (int) ((my - y - 3) / LINE), 0, starts.size() - 1);
		int start = starts.get(line);
		int rel = Math.max(0, (int) mx - (x + 4));
		move(start + Draw.substringByWidth(text.substring(start, lineEnd(starts, line)), rel).length());
		return true;
	}

	@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		if (!isFocused()) return false;
		boolean ctrl = (modifiers & InputConstants.MOD_CONTROL) != 0 || (modifiers & InputConstants.MOD_SUPER) != 0;
		switch (key) {
			case InputConstants.KEY_ESCAPE -> UiState.clearFocus();
			case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> insert("\n");
			case InputConstants.KEY_BACKSPACE -> {
				if (cursor > 0) edit(text.substring(0, cursor - 1) + text.substring(cursor), cursor - 1);
			}
			case InputConstants.KEY_DELETE -> {
				if (cursor < text.length()) edit(text.substring(0, cursor) + text.substring(cursor + 1), cursor);
			}
			case InputConstants.KEY_LEFT -> move(cursor - 1);
			case InputConstants.KEY_RIGHT -> move(cursor + 1);
			case InputConstants.KEY_UP -> moveLine(-1);
			case InputConstants.KEY_DOWN -> moveLine(1);
			case InputConstants.KEY_HOME -> {
				List<Integer> starts = lines();
				move(starts.get(lineOf(starts, cursor)));
			}
			case InputConstants.KEY_END -> {
				List<Integer> starts = lines();
				move(lineEnd(starts, lineOf(starts, cursor)));
			}
			case InputConstants.KEY_V -> {
				if (!ctrl) return false;
				insert(Minecraft.getInstance().keyboardHandler.getClipboard().replace("\r", ""));
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

	/** Up or Down by one visual line, keeping the column where it can. */
	private void moveLine(int delta) {
		List<Integer> starts = lines();
		int line = lineOf(starts, cursor);
		int target = line + delta;
		if (target < 0 || target >= starts.size()) return;
		int column = Draw.width(text.substring(starts.get(line), cursor));
		int start = starts.get(target);
		move(start + Draw.substringByWidth(text.substring(start, lineEnd(starts, target)), column).length());
	}

	private void move(int pos) {
		cursor = Math.clamp(pos, 0, text.length());
	}

	private void insert(String s) {
		if (text.length() + s.length() > maxLength) return;
		edit(text.substring(0, cursor) + s + text.substring(cursor), cursor + s.length());
	}

	private void edit(String next, int newCursor) {
		text = next;
		cursor = Math.clamp(newCursor, 0, text.length());
	}

	@Override
	public void onFocusLost() {
		onBlur.accept(text);
	}
}
