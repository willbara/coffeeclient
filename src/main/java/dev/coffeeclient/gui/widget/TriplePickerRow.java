package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.theme.Theme;
import dev.coffeeclient.setting.BlockPosSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Vector3dSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Full-width row with three channel sliders (X/Y/Z) folded underneath, opened by clicking the header.
 * Shared by {@link Vector3dSetting} and {@link BlockPosSetting} editors.
 */
class TriplePickerRow extends Container implements ScrollList.Relayout {
	private static final int HEAD = SettingRow.HEIGHT;
	private static final int SUB = 16;
	private static final String[] NAMES = {"X", "Y", "Z"};
	private static final int[] TRACKS = {0xFFD9534F, 0xFF5CB85C, 0xFF4F7FD9};

	private final String label;
	private final Supplier<String> value;
	private final List<Slider> sliders = new ArrayList<>();
	private boolean open;

	TriplePickerRow(String label, String description, Supplier<String> value, NumberSetting[] channels) {
		this.label = label;
		this.value = value;
		for (int i = 0; i < 3; i++) {
			int color = TRACKS[i];
			sliders.add(add(new Slider(channels[i]).trackColor(() -> color)));
		}
		height = HEAD;
		tooltip(description);
	}

	@Override
	public void relayout() {
		height = open ? HEAD + 3 * SUB + 2 : HEAD;
		for (int i = 0; i < sliders.size(); i++) {
			Slider s = sliders.get(i);
			s.visible = open;
			s.setBounds(x + width - 150, y + HEAD + i * SUB, 146, 14);
		}
	}

	@Override
	protected boolean tooltipAllowed(Ui ui) {
		return ui.mouseY < y + HEAD && ui.mouseX < x + width - 60;
	}

	@Override
	protected void draw(Ui ui) {
		relayout();
		Theme t = ui.theme;
		boolean overHead = ui.hoverAllowed && ui.mouseY >= y && ui.mouseY < y + HEAD && contains(ui.mouseX, ui.mouseY);
		if (overHead) Draw.rect(ui.g, x, y, width, HEAD, t.accent(0x16));
		Draw.text(ui.g, Draw.trim(label, width - 80), x + 8, y + 6, t.text);
		Draw.textRight(ui.g, value.get(), x + width - 20, y + 6, open ? t.accent : t.dimText);
		Draw.arrow(ui.g, x + width - 9, y + 8, 3, open ? 0 : 1, open ? t.accent : t.dimText);
		if (open) {
			for (int i = 0; i < sliders.size(); i++) Draw.text(ui.g, NAMES[i], x + width - 162, y + HEAD + i * SUB + 3, t.dimText);
		}
		drawChildren(ui);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (my < y + HEAD) {
			open = !open;
			relayout();
			return true;
		}
		return super.mouseClicked(mx, my, button);
	}
}

/** Editor row for a {@link Vector3dSetting}. */
final class VectorPickerRow extends TriplePickerRow {
	VectorPickerRow(Vector3dSetting s) {
		super(s.name(), s.description(), s::display, bind(s));
	}

	private static NumberSetting[] bind(Vector3dSetting s) {
		NumberSetting x = channel("X", s.x(), s.min(), s.max(), s.step(), s::setX);
		NumberSetting y = channel("Y", s.y(), s.min(), s.max(), s.step(), s::setY);
		NumberSetting z = channel("Z", s.z(), s.min(), s.max(), s.step(), s::setZ);
		s.onChange(v -> {
			x.set(v.x);
			y.set(v.y);
			z.set(v.z);
		});
		return new NumberSetting[]{x, y, z};
	}

	private static NumberSetting channel(String name, double value, double min, double max, double step, java.util.function.DoubleConsumer setter) {
		NumberSetting n = new NumberSetting(name, name, value, min, max, step);
		n.transientValue();
		n.onChange(v -> setter.accept(v));
		return n;
	}
}

/** Editor row for a {@link BlockPosSetting}. */
final class BlockPosPickerRow extends TriplePickerRow {
	BlockPosPickerRow(BlockPosSetting s) {
		super(s.name(), s.description(), s::display, bind(s));
	}

	private static NumberSetting[] bind(BlockPosSetting s) {
		NumberSetting x = channel("X", s.x(), s.min(), s.max(), s::setX);
		NumberSetting y = channel("Y", s.y(), s.min(), s.max(), s::setY);
		NumberSetting z = channel("Z", s.z(), s.min(), s.max(), s::setZ);
		s.onChange(v -> {
			x.set((double) v.getX());
			y.set((double) v.getY());
			z.set((double) v.getZ());
		});
		return new NumberSetting[]{x, y, z};
	}

	private static NumberSetting channel(String name, int value, int min, int max, java.util.function.IntConsumer setter) {
		NumberSetting n = new NumberSetting(name, name, value, min, max, 1);
		n.transientValue();
		n.onChange(v -> setter.accept((int) Math.round(v)));
		return n;
	}
}
