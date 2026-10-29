package dev.coffeeclient.module.modules.misc;

import dev.coffeeclient.module.Category;
import dev.coffeeclient.module.Module;
import dev.coffeeclient.module.modules.misc.Friends;
import dev.coffeeclient.setting.BooleanSetting;
import dev.coffeeclient.setting.Setting;
import dev.coffeeclient.setting.StringSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Sends a specified message to any player that enters render distance. */
public final class MessageAura extends Module {
	public final StringSetting message = setting(new StringSetting("Message", "The specified message sent to the player", "Coffee on Crack!", 256));
	public final BooleanSetting ignoreFriends = setting(new BooleanSetting("Ignore friends", "Will not send any messages to people friended", false));

	private final Set<UUID> seen = new HashSet<>();

	public MessageAura() {
		super("MessageAura", Category.MISC, "Sends a specified message to any player that enters render distance.");
	}

	@Override
	protected void onDisable() {
		seen.clear();
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null) return;

		for (Player p : mc().level.players()) {
			if (p == player || !p.isAlive()) continue;
			if (seen.contains(p.getUUID())) continue;
			if (player.distanceTo(p) > 64) continue;
			if (ignoreFriends.get() && Friends.is(p.getName().getString(), dev.coffeeclient.friend.FriendTrait.SPARED)) continue;

			seen.add(p.getUUID());
			if (mc().getConnection() != null) mc().getConnection().sendChat("/msg " + p.getName().getString() + " " + message.get());
		}
	}
}
