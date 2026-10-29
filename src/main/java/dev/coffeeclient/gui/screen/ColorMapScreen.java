package dev.coffeeclient.gui.screen;

import dev.coffeeclient.gui.CoffeeScreen;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.widget.Button;
import dev.coffeeclient.gui.widget.ColorPickerRow;
import dev.coffeeclient.gui.widget.Label;
import dev.coffeeclient.gui.widget.ScrollList;
import dev.coffeeclient.setting.ColorMapSetting;
import dev.coffeeclient.setting.ColorSetting;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/** One colour picker per entry of a {@link ColorMapSetting}, in a centred 4:3 window. */
public class ColorMapScreen extends CoffeeScreen {
	private final ColorMapSetting setting;
	private int frameX, contentY, frameW, contentH;

	public ColorMapScreen(@Nullable Screen parent, ColorMapSetting setting) {
		super(setting.name(), parent);
		this.setting = setting;
	}

	@Override
	protected void build() {
		int[] frame = centred43(460);
		frameX = frame[0];
		frameW = frame[2];
		int y = frame[1] + 4;
		String title = setting.name();
		root.add(new Label(title, Label.Style.TITLE)).setBounds(frameX + 8, y, Draw.width(title) + 2, 14);
		root.add(new Button("Done", this::onClose)).setBounds(frameX + frameW - 60, y - 1, 56, 16);
		root.add(new Label(setting::description, Label.Style.DIM))
			.setBounds(frameX + 18 + Draw.width(title), y, frameW - Draw.width(title) - 90, 14);
		contentY = y + 18;
		contentH = frame[1] + frame[3] - contentY;
		ScrollList list = root.add(new ScrollList().padding(1));
		list.setBounds(frameX + 1, contentY + 1, frameW - 2, contentH - 2);
		for (String id : setting.ids()) {
			// A stand-in setting per entry; its changes go straight into the map.
			ColorSetting entry = new ColorSetting(setting.label(id), id, setting.color(id));
			entry.onChange(argb -> setting.put(id, argb));
			list.add(new ColorPickerRow(entry));
		}
	}

	@Override
	protected void renderContent(Ui ui) {
		Draw.panel(ui.g, ui.theme, frameX, contentY, frameW, contentH);
		super.renderContent(ui);
	}
}
