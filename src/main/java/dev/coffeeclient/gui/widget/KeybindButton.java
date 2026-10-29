package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.UiState;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.util.Colors;
import dev.coffeeclient.util.KeyNames;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Key binding button with a small ✕ to clear it. Click to listen, press a key to bind, Esc to cancel.
 * Works for module binds and vanilla/Fabric key mappings through {@link Target}.
 */
public class KeybindButton extends Widget {
	public static final int CLEAR_W = 12;

	/** Bridge to whatever stores the key. */
	public interface Target {
		int key();

		void bind(int key);

		String display();
	}

	private final Target target;

	public KeybindButton(Target target) {
		this.target = target;
	}

	private int buttonW() {
		return width - CLEAR_W;
	}

	@Override
	protected void draw(Ui ui) {
		Theme t = ui.theme;
		boolean listening = isFocused();
		int bw = buttonW();
		boolean overButton = isHovered(ui) && ui.mouseX < x + bw;
		Draw.box(ui.g, x, y, bw - 2, height, overButton || listening ? t.controlHover : t.control,
			listening ? t.accent : overButton ? Colors.lerp(t.border, t.accent, 0.6f) : t.border);
		String label = listening ? "> Press a key <" : target.display();
		int color = listening ? t.accent : KeyNames.isBound(target.key()) ? t.text : t.dimText;
		Draw.textCentered(ui.g, Draw.trim(label, bw - 6), x + (bw - 2) / 2, y + (height - 8) / 2, color);
		boolean overClear = isHovered(ui) && ui.mouseX >= x + bw;
		Draw.cross(ui.g, x + bw + 2, y + height / 2 - 3, 7, overClear ? t.error : t.dimText);
		if (overClear) ui.tooltip("Clear binding");
		else if (listening) ui.tooltip("Press a key or mouse button", "Esc cancels, \u2715 clears");
	}

	@Override
	public boolean mouseBinding(double mx, double my, int button) {
		// The ✕ keeps its clearing behaviour; anywhere else on the button claims the click.
		if (!isFocused() || !contains(mx, my) || mx >= x + buttonW()) return false;
		target.bind(KeyNames.mouseKey(button));
		UiState.clearFocus();
		return true;
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (mx >= x + buttonW()) {
			target.bind(-1);
			UiState.clearFocus();
			return true;
		}
		if (button == LEFT) {
			UiState.focus(isFocused() ? null : this);
			return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		if (!isFocused()) return false;
		if (key != InputConstants.KEY_ESCAPE) target.bind(key);
		UiState.clearFocus();
		return true;
	}
}
