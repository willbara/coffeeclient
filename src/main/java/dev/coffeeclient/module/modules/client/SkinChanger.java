package dev.coffeeclient.module.modules.client;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.ModuleManager;
import dev.coffeeclient.setting.StringSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.List;

/**
 * Wears any player's skin, client-side: one for you and one for everyone else. The skin is looked
 * up by name through the game's own profile resolver and skin manager (which cache it), and swapped
 * in where the game asks a player for its skin, so the model, your first-person arm, the tab list
 * and the inventory all show it. Capes and elytras stay the wearer's own.
 */
public final class SkinChanger extends Module {
	public final StringSetting self = setting(new StringSetting("Your skin", "Player whose skin you wear (empty keeps yours)", "", 16));
	public final StringSetting others = setting(new StringSetting("Others' skin", "Player whose skin everyone else wears (empty keeps theirs)", "", 16));

	/** The loaded skins, and the names they were loaded for (so a stale reply is dropped). */
	private volatile PlayerSkin selfSkin, othersSkin;
	private String selfLoaded = "", othersLoaded = "";

	public SkinChanger() {
		super("SkinChanger", List.of("Skin Changer", "SkinChange"), Category.CLIENT, "Wear any player's skin (only you see it).");
	}

	@Override
	protected void onDisable() {
		selfSkin = othersSkin = null;
		selfLoaded = othersLoaded = "";
	}

	@Override
	public void onTick() {
		String s = self.get().trim(), o = others.get().trim();
		if (!s.equals(selfLoaded)) {
			selfLoaded = s;
			selfSkin = null;
			load(s, true);
		}
		if (!o.equals(othersLoaded)) {
			othersLoaded = o;
			othersSkin = null;
			load(o, false);
		}
	}

	private void load(String name, boolean forSelf) {
		if (name.isEmpty()) return;
		Minecraft mc = mc();
		ResolvableProfile.createUnresolved(name).resolveProfile(mc.services().profileResolver())
			.thenCompose(profile -> mc.getSkinManager().get(profile))
			.whenCompleteAsync((skin, error) -> {
				// A newer name was typed meanwhile, or the module went off: this reply is stale.
				if (!isEnabled() || !name.equals(forSelf ? selfLoaded : othersLoaded)) return;
				if (error != null || skin == null || skin.isEmpty()) {
					info("No skin found for " + name + ".");
					return;
				}
				if (forSelf) selfSkin = skin.get();
				else othersSkin = skin.get();
			}, mc);
	}

	/** PlayerInfoMixin: the skin shown for {@code name}, with the chosen one swapped in; the cape stays theirs. */
	public static PlayerSkin apply(PlayerSkin skin, String name) {
		SkinChanger m = ModuleManager.ifOn(SkinChanger.class);
		if (m == null) return skin;
		PlayerSkin custom = name.equals(mc().getUser().getName()) ? m.selfSkin : m.othersSkin;
		return custom == null ? skin : new PlayerSkin(custom.body(), skin.cape(), skin.elytra(), custom.model(), skin.secure());
	}

	@Override
	public String infoString() {
		return selfLoaded.isEmpty() ? null : selfLoaded;
	}
}
