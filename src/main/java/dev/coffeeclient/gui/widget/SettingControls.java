package dev.coffeeclient.gui.widget;

import dev.coffeeclient.gui.screen.ColorMapScreen;
import dev.coffeeclient.gui.screen.IdPickerScreen;
import dev.coffeeclient.gui.screen.StringListScreen;
import dev.coffeeclient.setting.ActionSetting;
import dev.coffeeclient.setting.BlockPosSetting;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorMapSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.KeybindSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.setting.StringListSetting;
import dev.coffeeclient.setting.StringSetting;
import dev.coffeeclient.setting.Vector3dSetting;
import net.minecraft.client.Minecraft;

/** Full-size editor rows for any setting (Settings screen and module config windows). */
public final class SettingControls {
	public static final int CONTROL_W = 127;

	private SettingControls() {
	}

	public static Widget row(Setting<?> s) {
		Widget row = switch (s) {
			case ColorSetting c -> new ColorPickerRow(c);
			case BooleanSetting b -> new SettingRow(b::name, new Toggle(b::get, b::toggle), 10, 10);
			case NumberSetting n -> new SettingRow(n.name(), new Slider(n), CONTROL_W + 30);
			case EnumSetting<?> e -> new SettingRow(e.name(), new CycleButton(e::display, e::cycle), CONTROL_W);
			case KeybindSetting k -> new SettingRow(k.name(), new KeybindButton(new KeybindButton.Target() {
				@Override
				public int key() {
					return k.key();
				}

				@Override
				public void bind(int key) {
					k.set(key);
				}

				@Override
				public String display() {
					return k.display();
				}
			}), CONTROL_W);
			case StringSetting str -> new SettingRow(str.name(), new TextField().text(str.get()).maxLength(str.maxLength()).onChange(str::set), CONTROL_W);
			case ActionSetting a -> new SettingRow(a.name(), new Button(a.name(), a::run), CONTROL_W);
			case StringListSetting sl -> new SettingRow(sl.name(), new Button(() -> sl.display() + "  ›", () -> {
				Minecraft mc = Minecraft.getInstance();
				mc.gui.setScreen(new StringListScreen(mc.gui.screen(), sl));
			}), CONTROL_W);
			case Vector3dSetting v -> new VectorPickerRow(v);
			case BlockPosSetting p -> new BlockPosPickerRow(p);
			case ColorMapSetting m -> new SettingRow(m.name(), new Button(() -> m.display() + "  ›", () -> {
				Minecraft mc = Minecraft.getInstance();
				mc.gui.setScreen(new ColorMapScreen(mc.gui.screen(), m));
			}), CONTROL_W);
			case IdListSetting l -> new SettingRow(l.name(), new Button(() -> l.display() + "  ›", () -> {
				Minecraft mc = Minecraft.getInstance();
				mc.gui.setScreen(new IdPickerScreen(mc.gui.screen(), l));
			}), CONTROL_W);
			default -> null;
		};
		if (row != null && !(row instanceof ColorPickerRow)) row.tooltip(s.description());
		return row;
	}
}
