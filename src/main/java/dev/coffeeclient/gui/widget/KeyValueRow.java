package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.theme.Theme;
import net.minecraft.client.Minecraft;

import java.util.Locale;
import java.util.function.Supplier;

/** Label left, value right-aligned, optional copy-to-clipboard icon. */
public class KeyValueRow extends Widget {
	public static final int HEIGHT = 12;
	private static final int COPY_W = 10;

	private final String label;
	private final Supplier<String> value;
	private Supplier<Integer> valueColor;
	private boolean copyable;
	private long copiedAt;

	public KeyValueRow(String label, Supplier<String> value) {
		this.label = label;
		this.value = value;
	}

	public KeyValueRow copyable() {
		this.copyable = true;
		return this;
	}

	public KeyValueRow valueColor(Supplier<Integer> color) {
		this.valueColor = color;
		return this;
	}

	@Override
	protected void draw(Ui ui) {
		Theme t = ui.theme;
		if (hover > 0.01f) CompactSettings.wash(ui, x - 3, y, width + 6, height, hover * 0.85f);
		int ty = y + (height - 8) / 2;
		Draw.text(ui.g, label, x, ty, t.dimText);
		int right = x + width - (copyable ? COPY_W + 2 : 0);
		String v = Draw.trim(value.get(), right - x - Draw.width(label) - 8);
		Draw.textRight(ui.g, v, right, ty, valueColor != null ? valueColor.get() : t.text);
		if (copyable) {
			boolean overIcon = ui.hoverAllowed && ui.mouseX >= x + width - COPY_W && contains(ui.mouseX, ui.mouseY);
			boolean recently = System.currentTimeMillis() - copiedAt < 1200;
			int c = recently ? t.accent : overIcon ? t.strongText() : t.dimText;
			if (recently) Draw.check(ui.g, x + width - 8, ty + 1, c);
			else Draw.copyIcon(ui.g, x + width - 8, ty, c);
			if (overIcon) ui.tooltip(recently ? "Copied!" : "Copy " + label.toLowerCase(Locale.ROOT));
		}
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (copyable && button == LEFT) {
			Minecraft.getInstance().keyboardHandler.setClipboard(value.get());
			copiedAt = System.currentTimeMillis();
			return true;
		}
		return false;
	}
}
