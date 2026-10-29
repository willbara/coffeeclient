package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.util.Colors;

import java.util.ArrayList;
import java.util.List;

/** Full-width colour row: swatch + editable hex; click the row to open R/G/B/A sliders underneath. */
public class ColorPickerRow extends Container implements ScrollList.Relayout {
	private static final int HEAD = SettingRow.HEIGHT;
	private static final int SUB = 16;

	private final ColorSetting setting;
	private final List<Slider> sliders = new ArrayList<>();
	private final TextField hex;
	private boolean open;
	private final int channels;

	public ColorPickerRow(ColorSetting setting) {
		this(setting, false, true);
	}

	/** @param alpha whether to offer an alpha slider (off for colours that are always drawn opaque) */
	public ColorPickerRow(ColorSetting setting, boolean startOpen, boolean alpha) {
		this.setting = setting;
		this.open = startOpen;
		this.channels = alpha ? 4 : 3;
		String[] names = {"Red", "Green", "Blue", "Alpha"};
		int[] tracks = {0xFFD9534F, 0xFF5CB85C, 0xFF4F7FD9, 0xFFC8C8C8};
		for (int i = 0; i < channels; i++) {
			int ch = i, color = tracks[i];
			NumberSetting n = new NumberSetting(names[i], names[i], setting.channel(ch), 0, 255, 1);
			n.transientValue();
			n.onChange(v -> setting.setChannel(ch, v.intValue()));
			setting.onChange(v -> n.set((double) setting.channel(ch)));
			sliders.add(add(new Slider(n).trackColor(() -> color)));
		}
		hex = add(new TextField().maxLength(7).centered().filter(v -> v.matches("#?[0-9a-fA-F]{0,6}")).onChange(v -> {
			String digits = v.replace("#", "");
			if (digits.length() == 6) setting.set((setting.argb() & 0xFF000000) | Integer.parseInt(digits, 16));
		}));
		height = HEAD;
		tooltip(setting.description());
	}

	public String label() {
		return setting.name();
	}

	@Override
	public void relayout() {
		hex.setBounds(x + width - 84, y + 3, 56, 14);
		height = open ? HEAD + channels * SUB + 2 : HEAD;
		for (int i = 0; i < sliders.size(); i++) {
			Slider s = sliders.get(i);
			s.visible = open;
			s.setBounds(x + width - 150, y + HEAD + i * SUB, 146, 14);
		}
	}

	@Override
	protected boolean tooltipAllowed(Ui ui) {
		return ui.mouseY < y + HEAD && ui.mouseX < x + width - 90;
	}

	@Override
	protected void draw(Ui ui) {
		relayout();
		Theme t = ui.theme;
		boolean overHead = ui.hoverAllowed && ui.mouseY >= y && ui.mouseY < y + HEAD && contains(ui.mouseX, ui.mouseY);
		if (overHead) Draw.rect(ui.g, x, y, width, HEAD, t.accent(0x16));
		Draw.text(ui.g, Draw.trim(setting.name(), width - 100), x + 8, y + 6, t.text);
		if (!hex.isFocused() && !hex.text().equals(setting.display())) hex.text(setting.display());
		Draw.box(ui.g, x + width - 20, y + 4, 16, 12, Colors.withAlpha(setting.argb(), 255), open ? t.accent : t.border);
		if (open) {
			String[] labels = {"R", "G", "B", "A"};
			for (int i = 0; i < channels; i++) Draw.text(ui.g, labels[i], x + width - 162, y + HEAD + i * SUB + 3, t.dimText);
		}
		drawChildren(ui);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (hex.contains(mx, my)) return hex.mouseClicked(mx, my, button);
		if (my < y + HEAD) {
			open = !open;
			relayout();
			return true;
		}
		return super.mouseClicked(mx, my, button);
	}
}
