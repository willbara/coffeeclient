package dev.coffeeclient.gui.screen;

import dev.coffeeclient.gui.CoffeeScreen;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.UiState;
import dev.coffeeclient.gui.widget.Button;
import dev.coffeeclient.gui.widget.IdListPanel;
import dev.coffeeclient.gui.widget.Label;
import dev.coffeeclient.setting.IdListSetting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import org.jspecify.annotations.Nullable;

/** Editor for an {@link IdListSetting} (wraps {@link IdListPanel}) in a centred 4:3 window. */
public class IdPickerScreen extends CoffeeScreen {
	private final IdListSetting setting;
	private IdListPanel panel;
	private int frameX, frameY, frameW, frameH, contentY, contentH;

	public IdPickerScreen(@Nullable Screen parent, IdListSetting setting) {
		super(setting.name(), parent);
		this.setting = setting;
	}

	@Override
	protected void build() {
		int[] frame = centred43(460);
		frameX = frame[0];
		frameY = frame[1];
		frameW = frame[2];
		frameH = frame[3];
		int y = frameY + 4;
		String title = setting.name();
		root.add(new Label(title, Label.Style.TITLE)).setBounds(frameX + 8, y, Draw.width(title) + 2, 14);
		root.add(new Button("Done", this::onClose)).setBounds(frameX + frameW - 60, y - 1, 56, 16);
		root.add(new Label(setting::description, Label.Style.DIM)).tooltip(() -> java.util.List.of(setting.description()))
			.setBounds(frameX + 18 + Draw.width(title), y, frameW - Draw.width(title) - 90, 14);
		contentY = y + 18;
		contentH = frameY + frameH - contentY;
		panel = root.add(new IdListPanel(setting));
		panel.setBounds(frameX + 6, contentY + 6, frameW - 12, contentH - 10);
	}

	@Override
	protected void renderContent(Ui ui) {
		Draw.panel(ui.g, ui.theme, frameX, contentY, frameW, contentH);
		super.renderContent(ui);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (UiState.focused() == null && event.isAllowedChatCharacter() && !Character.isWhitespace(event.codepoint())) panel.searchField().focus();
		return super.charTyped(event);
	}
}
