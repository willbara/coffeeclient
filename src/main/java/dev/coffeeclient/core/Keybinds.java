package dev.coffeeclient.core;

import com.mojang.blaze3d.platform.InputConstants;
import dev.coffeeclient.gui.screen.ClickGuiScreen;
import dev.coffeeclient.gui.screen.DashboardScreen;
import dev.coffeeclient.gui.screen.FriendsScreen;
import dev.coffeeclient.gui.screen.HistoryScreen;
import dev.coffeeclient.gui.screen.HudEditorScreen;
import dev.coffeeclient.gui.screen.KeybindsScreen;
import dev.coffeeclient.gui.screen.MacrosScreen;
import dev.coffeeclient.gui.screen.ProfilesScreen;
import dev.coffeeclient.gui.screen.SettingsScreen;
import dev.coffeeclient.util.KeyNames;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Global key mappings, registered through Fabric so they also appear (and can be rebound) in vanilla's
 * Controls screen. Vanilla persists them in options.txt.
 */
public final class Keybinds {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(CoffeeClient.MOD_ID, "main"));

	public record Entry(String label, KeyMapping mapping) {
	}

	public final KeyMapping dashboard = register("dashboard", InputConstants.KEY_RSHIFT);
	public final KeyMapping clickGui = register("click_gui", InputConstants.KEY_RCONTROL);
	public final KeyMapping hudEditor = register("hud_editor", InputConstants.UNKNOWN.getValue());
	public final KeyMapping macros = register("macros", InputConstants.UNKNOWN.getValue());
	public final KeyMapping profiles = register("profiles", InputConstants.UNKNOWN.getValue());
	public final KeyMapping friends = register("friends", InputConstants.UNKNOWN.getValue());
	public final KeyMapping settings = register("settings", InputConstants.UNKNOWN.getValue());
	public final KeyMapping keybindList = register("keybinds", InputConstants.UNKNOWN.getValue());
	public final KeyMapping history = register("history", InputConstants.UNKNOWN.getValue());

	private static KeyMapping register(String id, int key) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + CoffeeClient.MOD_ID + "." + id, InputConstants.Type.KEYSYM, key, CATEGORY));
	}

	public List<Entry> entries() {
		return List.of(
			new Entry("Open dashboard", dashboard),
			new Entry("Open ClickGUI", clickGui),
			new Entry("Open HUD editor", hudEditor),
			new Entry("Open macros", macros),
			new Entry("Open profiles", profiles),
			new Entry("Open friends", friends),
			new Entry("Open settings", settings),
			new Entry("Open keybinds", keybindList),
			new Entry("Open history", history));
	}

	/** Rebind a mapping (keyboard key, mouse button, or -1 to unbind) and persist through vanilla options. */
	public static void rebind(KeyMapping mapping, int key) {
		mapping.setKey(KeyNames.toKey(key));
		KeyMapping.resetMapping();
		Minecraft.getInstance().options.save();
	}

	public void tick(Minecraft mc) {
		while (dashboard.consumeClick()) mc.gui.setScreen(new DashboardScreen(null));
		while (clickGui.consumeClick()) mc.gui.setScreen(new ClickGuiScreen(null));
		while (hudEditor.consumeClick()) mc.gui.setScreen(new HudEditorScreen(null));
		while (macros.consumeClick()) mc.gui.setScreen(new MacrosScreen(null));
		while (profiles.consumeClick()) mc.gui.setScreen(new ProfilesScreen(null));
		while (friends.consumeClick()) mc.gui.setScreen(new FriendsScreen(null));
		while (settings.consumeClick()) mc.gui.setScreen(new SettingsScreen(null));
		while (keybindList.consumeClick()) mc.gui.setScreen(new KeybindsScreen(null));
		while (history.consumeClick()) mc.gui.setScreen(new HistoryScreen(null));
	}
}
