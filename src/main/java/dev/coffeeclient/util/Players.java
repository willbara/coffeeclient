package dev.coffeeclient.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.UUID;

/** Tab-list queries shared by modules that need to tell players from NPCs and bots. */
public final class Players {
	private Players() {
	}

	/**
	 * True when the player is actually shown in the tab list. A {@link PlayerInfo} can sit in the
	 * client's player-info map with {@code listed=false}: Citizens does this for player NPCs for a
	 * couple of ticks so the client loads their skin, then removes it.
	 */
	public static boolean isListed(UUID id) {
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection == null) return false;
		PlayerInfo info = connection.getPlayerInfo(id);
		return info != null && connection.getListedOnlinePlayers().contains(info);
	}
}
