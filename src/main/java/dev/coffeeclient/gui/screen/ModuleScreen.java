package dev.coffeeclient.gui.screen;

import dev.coffeeclient.gui.CoffeeScreen;
import dev.coffeeclient.gui.Draw;
import dev.coffeeclient.gui.Ui;
import dev.coffeeclient.gui.UiState;
import dev.coffeeclient.gui.widget.Button;
import dev.coffeeclient.gui.widget.IdListPanel;
import dev.coffeeclient.gui.widget.Label;
import dev.coffeeclient.gui.widget.Panel;
import dev.coffeeclient.gui.widget.ResetRow;
import dev.coffeeclient.gui.widget.ScrollList;
import dev.coffeeclient.gui.widget.SectionHeader;
import dev.coffeeclient.gui.widget.SettingControls;
import dev.coffeeclient.gui.widget.StringListPanel;
import dev.coffeeclient.gui.widget.TextField;
import dev.coffeeclient.gui.widget.Widget;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.setting.StringListSetting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full window for modules with advanced configuration: settings grouped into sections on the left, and
 * the module's main selection list (if any) embedded on the right.
 */
public class ModuleScreen extends CoffeeScreen {
	private final Module module;
	private final List<Map.Entry<Widget, Setting<?>>> rows = new ArrayList<>();
	private final List<Map.Entry<Widget, List<Setting<?>>>> headers = new ArrayList<>();
	private TextField embeddedField;
	private ScrollList list;
	private int frameX, frameY, frameW, frameH, listW, contentY, contentH, settingsW;

	public ModuleScreen(@Nullable Screen parent, Module module) {
		super(module.name(), parent);
		this.module = module;
	}

	@Override
	protected void build() {
		int width = layoutWidth();
		rows.clear();
		headers.clear();
		Setting<?> embedded = module.embeddedList();
		boolean embeddable = embedded instanceof IdListSetting || embedded instanceof StringListSetting;

		// A 4:3 window centred on the screen. Height follows the width cap instead of filling the screen.
		boolean wantSplit = embeddable && width >= 480;
		int[] frame = centred43(wantSplit ? 680 : 460);
		frameX = frame[0];
		frameY = frame[1];
		frameW = frame[2];
		frameH = frame[3];

		boolean split = embeddable && frameW >= 432;

		int y = frameY + 4;
		root.add(new Label(module.name(), Label.Style.TITLE)).setBounds(frameX + 8, y, Draw.width(module.name()) + 2, 14);
		root.add(new Label(module.description(), Label.Style.DIM)).tooltip(module.description())
			.setBounds(frameX + 18 + Draw.width(module.name()), y, frameW - Draw.width(module.name()) - 156, 14);
		if (module.isToggleable()) {
			root.add(new Button(() -> module.isEnabled() ? "Enabled" : "Disabled", module::toggle) {
				@Override
				protected void draw(Ui ui) {
					super.draw(ui);
					int light = module.isEnabled() ? ui.theme.accent : ui.theme.dimText;
					if (ui.theme.glass()) {
						if (module.isEnabled()) dev.coffeeclient.gui.fx.Shapes.glowDot(ui.g, x + 7.5f, y + height / 2f, 2, 3, ui.theme.accent(0x90));
						dev.coffeeclient.gui.fx.Shapes.disc(ui.g, x + 7.5f, y + height / 2f, 2, light);
					} else {
						Draw.rect(ui.g, x + 2, y + 2, 2, height - 4, light);
					}
				}
			}).setBounds(frameX + frameW - 132, y - 1, 64, 16);
		}
		root.add(new Button("Back", this::onClose)).setBounds(frameX + frameW - 64, y - 1, 56, 16);

		contentY = y + 18;
		contentH = frameY + frameH - contentY;
		listW = split ? (frameW - 6) * 5 / 11 : 0;
		settingsW = split ? frameW - listW - 6 : frameW;
		list = root.add(new ScrollList().padding(1));
		list.setBounds(frameX + 1, contentY + 1, settingsW - 2, contentH - 2);

		Map<String, List<Setting<?>>> groups = new LinkedHashMap<>();
		if (module.isToggleable() && !module.isAction()) groups.computeIfAbsent("General", g -> new ArrayList<>()).add(module.keybind());
		if (module.listable()) groups.computeIfAbsent("General", g -> new ArrayList<>()).add(module.shownInList());
		for (Setting<?> s : module.settings()) {
			if (split && s == embedded) continue;
			groups.computeIfAbsent(s.group().isEmpty() ? "General" : s.group(), g -> new ArrayList<>()).add(s);
		}
		for (var group : groups.entrySet()) {
			Widget header = list.add(new SectionHeader(group.getKey()));
			headers.add(Map.entry(header, group.getValue()));
			for (Setting<?> s : group.getValue()) {
				Widget row = ResetRow.wrap(SettingControls.row(s), s);
				if (row == null) continue;
				list.add(row);
				rows.add(Map.entry(row, s));
			}
		}

		embeddedField = null;
		if (split) {
			int lx = frameX + settingsW + 6;
			root.add(new Panel(embedded.name()).subtitle(embedded::display)).setBounds(lx, contentY, listW, contentH);
			Widget panel = switch (embedded) {
				case IdListSetting l -> {
					IdListPanel p = new IdListPanel(l);
					embeddedField = p.searchField();
					yield p;
				}
				case StringListSetting s -> {
					StringListPanel p = new StringListPanel(s);
					embeddedField = p.entryField();
					yield p;
				}
				default -> null;
			};
			if (panel != null) {
				root.add(panel);
				panel.setBounds(lx + Panel.PAD, contentY + Panel.HEADER + 4, listW - Panel.PAD * 2, contentH - Panel.HEADER - 8);
			}
		}
	}

	/** The title bar (clear of its buttons) drags the window. */
	@Override
	protected boolean windowHandle(double mx, double my) {
		return mx >= frameX && mx < frameX + frameW && my >= frameY && my < contentY;
	}

	/** Keep at least a corner of the title bar on screen. */
	@Override
	protected void clampWindow() {
		float minX = 40 - frameX - frameW, maxX = layoutWidth() - 40 - frameX;
		float minY = -frameY, maxY = layoutHeight() - 20 - frameY;
		windowX = Math.max(minX, Math.min(maxX, windowX));
		windowY = Math.max(minY, Math.min(maxY, windowY));
	}

	@Override
	protected void renderContent(Ui ui) {
		for (var e : rows) e.getKey().visible = e.getValue().isVisible();
		// A section whose settings are all hidden by the current mode hides its title too.
		for (var e : headers) e.getKey().visible = e.getValue().stream().anyMatch(Setting::isVisible);
		Draw.panel(ui.g, ui.theme, frameX, contentY, settingsW, contentH);
		super.renderContent(ui);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (embeddedField != null && UiState.focused() == null && event.isAllowedChatCharacter() && !Character.isWhitespace(event.codepoint())) {
			embeddedField.focus();
		}
		return super.charTyped(event);
	}
}
