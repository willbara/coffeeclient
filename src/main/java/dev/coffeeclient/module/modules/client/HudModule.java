package dev.coffeeclient.module.modules.client;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.ColorSetting;
import dev.coffeeclient.setting.EnumSetting;
import dev.coffeeclient.setting.NumberSetting;
import dev.coffeeclient.setting.StringListSetting;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Master switch and element toggles for the HUD overlay. Positions are edited in the HUD editor. */
public final class HudModule extends Module {
	public enum ListStyle { LINE, BOX, MINIMAL, GLOW, PILL }

	public enum ListSort { LENGTH, ALPHABETICAL }

	public enum ListColors { CASCADE, FADE, STATIC }

	public enum ListAnimation { SLIDE, FADE, NONE }

	public enum WatermarkStyle { CHIP, GRADIENT, CUP }

	public final BooleanSetting moduleList = setting(new BooleanSetting("Module list", "List enabled modules", true).group("Module list"));
	public final EnumSetting<ListStyle> listStyle = setting(new EnumSetting<>("List style", "Module list look: accent edge line, boxed rows, plain text, glowing, or rounded pills", ListStyle.LINE).group("Module list"));
	public final EnumSetting<ListSort> listSort = setting(new EnumSetting<>("List sort", "Order rows by width, longest first, or by name", ListSort.LENGTH).group("Module list"));
	public final EnumSetting<ListColors> listColors = setting(new EnumSetting<>("Name colors", "Moving accent down the list, text fading into the accent, or one fixed color", ListColors.CASCADE).group("Module list"));
	public final ColorSetting listColor = setting(new ColorSetting("Name color", "Module name color for the fixed color mode", 0xFFFFFFFF).group("Module list"));
	public final BooleanSetting listRainbow = setting(new BooleanSetting("Rainbow", "Cycle the list accent through the rainbow, shifting hue down the rows", false).group("Module list"));
	public final NumberSetting rainbowSpeed = setting(new NumberSetting("Rainbow speed", "Rainbow cycles per second", 0.05, 0.01, 1, 0.01).group("Module list"));
	public final NumberSetting rainbowSpread = setting(new NumberSetting("Rainbow spread", "Hue shift from one row to the next", 0.01, 0.001, 0.1, 0.001).group("Module list"));
	public final NumberSetting rainbowSaturation = setting(new NumberSetting("Rainbow saturation", "Rainbow color saturation", 1, 0, 1, 0.05).group("Module list"));
	public final NumberSetting rainbowBrightness = setting(new NumberSetting("Rainbow brightness", "Rainbow color brightness", 1, 0, 1, 0.05).group("Module list"));
	public final EnumSetting<ListAnimation> listAnimation = setting(new EnumSetting<>("List animation", "How rows appear and leave: slide in from the edge, fade, or instantly", ListAnimation.SLIDE).group("Module list"));
	public final BooleanSetting moduleInfo = setting(new BooleanSetting("Module info", "Show each module's mode or status beside its name", true).group("Module list"));
	public final BooleanSetting infoBrackets = setting(new BooleanSetting("Info brackets", "Wrap module info in [brackets]", false).group("Module list"));
	public final BooleanSetting listLowercase = setting(new BooleanSetting("Lowercase", "Show module names in lowercase", false).group("Module list"));
	/** Per-category filters, registered in the constructor so they close out the module list group. */
	private final Map<Category, BooleanSetting> listCategories = new EnumMap<>(Category.class);

	public final BooleanSetting watermark = setting(new BooleanSetting("Watermark", "Client name in the corner", false).group("Elements"));
	public final EnumSetting<WatermarkStyle> watermarkStyle = setting(new EnumSetting<>("Watermark style", "Accent chip, gradient bar, or a steaming cup", WatermarkStyle.CUP).group("Elements"));
	public final BooleanSetting direction = setting(new BooleanSetting("Direction", "Show the compass direction and the horizontal axes you are facing", true).group("Elements"));
	public final BooleanSetting netherCoords = setting(new BooleanSetting("Nether coords", "Linked Nether/Overworld position", true).group("Elements"));
	public final BooleanSetting position = setting(new BooleanSetting("Position", "Show block X, Y, Z separately from dimension coordinates", true).group("Elements"));
	public final BooleanSetting speed = setting(new BooleanSetting("Speed", "Horizontal speed", true).group("Elements"));
	public final BooleanSetting text = setting(new BooleanSetting("Text", "Custom Starscript text lines", false).group("Elements"));
	public final StringListSetting textLines = setting(new StringListSetting("Text lines", "Starscript templates, e.g. {player.pos.x}", List.of("{coffee.name} {coffee.version}"), 256).group("Elements"));
	public final BooleanSetting keyboard = setting(new BooleanSetting("Keyboard", "WASD/space key display", false).group("Elements"));
	public final BooleanSetting lagNotifier = setting(new BooleanSetting("Lag notifier", "Warn when the server stalls", true).group("Elements"));
	public final BooleanSetting backgrounds = setting(new BooleanSetting("Backgrounds", "Panel fill behind HUD text", true).group("Elements"));
	public final ColorSetting backgroundColor = setting(new ColorSetting("Background color", "HUD panel fill color and opacity", 0xB0000000).group("Elements"));

	public HudModule() {
		super("Hud", Category.CLIENT, "Configurable overlay: module list, direction, Nether coords, position, speed, text, keyboard and lag notifier. Drag elements in the HUD editor.");
		backgroundColor.visibleWhen(backgrounds::get);
		listStyle.visibleWhen(moduleList::get);
		listSort.visibleWhen(moduleList::get);
		listColors.visibleWhen(moduleList::get);
		listColor.visibleWhen(() -> moduleList.get() && listColors.get() == ListColors.STATIC);
		listRainbow.visibleWhen(moduleList::get);
		for (NumberSetting n : List.of(rainbowSpeed, rainbowSpread, rainbowSaturation, rainbowBrightness)) n.visibleWhen(() -> moduleList.get() && listRainbow.get());
		listAnimation.visibleWhen(moduleList::get);
		moduleInfo.visibleWhen(moduleList::get);
		infoBrackets.visibleWhen(() -> moduleList.get() && moduleInfo.get());
		listLowercase.visibleWhen(moduleList::get);
		for (Category c : Category.values()) {
			listCategories.put(c, setting(new BooleanSetting("Show " + c.displayName(), "List " + c.displayName() + " modules", true).group("Module list")));
			listCategories.get(c).visibleWhen(moduleList::get);
		}
		watermarkStyle.visibleWhen(watermark::get);
		restoreEnabled(true);
	}

	public boolean listsCategory(Category c) {
		return listCategories.get(c).get();
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
