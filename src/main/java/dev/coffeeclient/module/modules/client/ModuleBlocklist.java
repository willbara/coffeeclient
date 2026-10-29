package dev.coffeeclient.module.modules.client;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.IdListSetting;
import dev.coffeeclient.setting.StringListSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.List;
import java.util.Locale;

/**
 * Keeps chosen modules off on chosen servers: they are switched off when you join, and refuse to
 * switch on while you are there. A server matches when its address contains one of the entries.
 */
public final class ModuleBlocklist extends Module {
	public final StringListSetting servers = setting(new StringListSetting("Servers", "Part of a server address, such as \"hypixel\" or \"play.example.net\"", List.of(), 64));
	public final IdListSetting modules = setting(new IdListSetting("Blocked modules", "Modules that cannot run on those servers",
		List.of(), () -> CoffeeClient.modules().all().stream().filter(Module::isToggleable).map(Module::name).sorted().toList(), false));

	public ModuleBlocklist() {
		super("ModuleBlocklist", List.of("TooManyHax", "SafeMode"), Category.CLIENT, "Keeps chosen modules off on chosen servers.");
		restoreEnabled(true);
	}

	@Override
	public boolean hasConfigScreen() {
		return true;
	}

	@Override
	public IdListSetting embeddedList() {
		return modules;
	}

	/** Module.setEnabled: true when {@code module} may not switch on here. */
	public static boolean blocks(Module module) {
		ModuleBlocklist b = ModuleManager.ifOn(ModuleBlocklist.class);
		if (b == null || module == b || !b.onListedServer() || !b.modules.contains(module.name())) return false;
		b.info(module.name() + " is blocked on this server");
		return true;
	}

	/** Called when you join a server: switches off the modules that are blocked there. */
	public static void onJoin() {
		ModuleBlocklist b = ModuleManager.ifOn(ModuleBlocklist.class);
		if (b == null || !b.onListedServer()) return;
		int off = 0;
		for (Module m : CoffeeClient.modules().all()) {
			if (m == b || !m.isEnabled() || !b.modules.contains(m.name())) continue;
			m.setEnabledSilently(false);
			off++;
		}
		if (off > 0) b.info("Switched off " + off + " module" + (off == 1 ? "" : "s") + " blocked on this server");
	}

	private boolean onListedServer() {
		ServerData server = Minecraft.getInstance().getCurrentServer();
		if (server == null) return false;
		return matches(server.ip, servers.get());
	}

	/** Whether {@code address} contains any non-blank entry, ignoring case. */
	static boolean matches(String address, List<String> entries) {
		String lower = address.toLowerCase(Locale.ROOT);
		for (String entry : entries) {
			String part = entry.trim().toLowerCase(Locale.ROOT);
			if (!part.isEmpty() && lower.contains(part)) return true;
		}
		return false;
	}
}
