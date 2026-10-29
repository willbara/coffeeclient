package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.fx.UiSounds;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.KeybindSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.util.Colors;

import java.util.List;
import java.util.Objects;

/** A setting row with a small reset wheel on its right edge that restores the default value. */
public class ResetRow extends Container implements ScrollList.Relayout {
	private static final int WHEEL_W = 16;

	private final Widget inner;
	private final Setting<?> setting;
	private final Widget wheel;

	private ResetRow(Widget inner, Setting<?> setting) {
		this.inner = add(inner);
		this.setting = setting;
		this.wheel = add(new Wheel());
		this.height = inner.height;
	}

	/** Whether this kind of setting can be reset from a row (lists, text and actions have no simple default). */
	public static boolean supports(Setting<?> s) {
		return s instanceof BooleanSetting || s instanceof NumberSetting || s instanceof EnumSetting<?>
			|| s instanceof ColorSetting || s instanceof KeybindSetting;
	}

	/** Wraps {@code row} with a reset wheel when its setting supports one. */
	public static Widget wrap(Widget row, Setting<?> s) {
		return row == null || !supports(s) ? row : new ResetRow(row, s);
	}

	private boolean atDefault() {
		return Objects.equals(setting.get(), setting.defaultValue());
	}

	@Override
	public void relayout() {
		inner.setBounds(x, y, width - WHEEL_W, inner.height);
		if (inner instanceof ScrollList.Relayout r) r.relayout();
		height = inner.height;
		wheel.setBounds(x + width - WHEEL_W, y + (SettingRow.HEIGHT - 14) / 2, WHEEL_W - 2, 14);
	}

	@Override
	protected boolean tooltipAllowed(Ui ui) {
		return ui.mouseX < wheel.x;
	}

	@Override
	protected void draw(Ui ui) {
		relayout();
		drawChildren(ui);
	}

	private final class Wheel extends Widget {
		Wheel() {
			tooltip(() -> atDefault() ? List.of() : List.of("Reset to default"));
		}

		@Override
		protected void draw(Ui ui) {
			boolean idle = atDefault();
			int color = idle ? Colors.withAlpha(ui.theme.dimText, 0x60) : Colors.lerp(ui.theme.dimText, ui.theme.accent, hover);
			float cx = x + width / 2f, cy = y + height / 2f;
			Draw.ring(ui.g, cx, cy, 3.5f, 1f, color);
			// arrow head on the open top-right of the ring
			Draw.rect(ui.g, Math.round(cx) + 2, Math.round(cy) - 5, 3, 1, color);
			Draw.rect(ui.g, Math.round(cx) + 4, Math.round(cy) - 5, 1, 3, color);
		}

		@Override
		public boolean mouseClicked(double mx, double my, int button) {
			if (button != LEFT || atDefault()) return true;
			UiSounds.click(0.9f);
			setting.reset();
			return true;
		}
	}
}
