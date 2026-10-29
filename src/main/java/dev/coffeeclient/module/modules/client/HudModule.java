package dev.coffeeclient.module.modules.client;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.StringListSetting;

import java.util.List;

/** Master switch and element toggles for the HUD overlay. Positions are edited in the HUD editor. */
public final class HudModule extends Module {
	public final BooleanSetting moduleList = setting(new BooleanSetting("Module list", "List enabled modules", true));
	public enum ListStyle { LINE, BOX, MINIMAL, GLOW, PILL }

	public enum WatermarkStyle { CHIP, GRADIENT, CUP }

	public final EnumSetting<ListStyle> listStyle = setting(new EnumSetting<>("List style", "Module list look: accent edge line, boxed rows, plain text, glowing, or rounded pills", ListStyle.LINE));
	public final BooleanSetting listCascade = setting(new BooleanSetting("Accent cascade", "Module names take the moving accent, flowing down the list", true));
	public final BooleanSetting watermark = setting(new BooleanSetting("Watermark", "Client name in the corner", false));
	public final EnumSetting<WatermarkStyle> watermarkStyle = setting(new EnumSetting<>("Watermark style", "Accent chip, gradient bar, or a steaming cup", WatermarkStyle.CUP));
	public final BooleanSetting moduleInfo = setting(new BooleanSetting("Module info", "Show each module's mode or status beside its name", true));
	public final BooleanSetting direction = setting(new BooleanSetting("Direction", "Show the compass direction and the horizontal axes you are facing", true));
	public final BooleanSetting netherCoords = setting(new BooleanSetting("Nether coords", "Linked Nether/Overworld position", true));
	public final BooleanSetting position = setting(new BooleanSetting("Position", "Show block X, Y, Z separately from dimension coordinates", true));
	public final BooleanSetting speed = setting(new BooleanSetting("Speed", "Horizontal speed", true));
	public final BooleanSetting text = setting(new BooleanSetting("Text", "Custom Starscript text lines", false));
	public final StringListSetting textLines = setting(new StringListSetting("Text lines", "Starscript templates, e.g. {player.pos.x}", List.of("{coffee.name} {coffee.version}"), 256));
	public final BooleanSetting keyboard = setting(new BooleanSetting("Keyboard", "WASD/space key display", false));
	public final BooleanSetting lagNotifier = setting(new BooleanSetting("Lag notifier", "Warn when the server stalls", true));
	public final BooleanSetting backgrounds = setting(new BooleanSetting("Backgrounds", "Panel fill behind HUD text", true));
	public final ColorSetting backgroundColor = setting(new ColorSetting("Background color", "HUD panel fill color and opacity", 0xB0000000));

	public HudModule() {
		super("Hud", Category.CLIENT, "Configurable overlay: module list, direction, Nether coords, position, speed, text, keyboard and lag notifier. Drag elements in the HUD editor.");
		backgroundColor.visibleWhen(backgrounds::get);
		listStyle.visibleWhen(moduleList::get);
		listCascade.visibleWhen(moduleList::get);
		watermarkStyle.visibleWhen(watermark::get);
		restoreEnabled(true);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	/** The HUD row opens the editor; its own settings are one click away from there. */
	@Override
	public net.minecraft.client.gui.screens.Screen configScreen(net.minecraft.client.gui.screens.Screen parent) {
		return new dev.coffeeclient.gui.screen.HudEditorScreen(parent);
	}

	@Override
	public StringListSetting embeddedList() {
		return textLines;
	}
}
